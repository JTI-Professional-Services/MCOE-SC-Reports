/*

CLEANUP_SCR_DATA
SC Reports Data Cleanup Script
Cleans up converted data to ensure compatibility with SC Reports

-- META --
Author: J Reynolds
Date:   Jun 2026
Jira #: MCOE-2635

-- INPUT --
_debug       {Boolean} Print items to log

-- OUTPUT --
fixed    {List} Items that were updated by the script.
flagged  {List} Items that need manual review

*/

final SCRIPT = "CLEANUP_SCR_DATA"
logger.info("$SCRIPT: Starting ...")
fixed = []
flagged = []
cpcCrims = 0

/**** QUERY - CF_BEGINDATE */
logger.info("$SCRIPT: Building query ...")
Where where = new Where()
where.addIsNull("cf_beginDate")
where.addGreaterThan("case.filingDate",DateUtil.parse("01/01/2010"))
where.addNotLike("case.caseType","SUB%")
where.addNotLike("case.status", "%NONSC%")
where.addGreaterThan("dispositionDate",DateUtil.parse("01/01/2023"))

Or or = new Or()
or.add(new Where().addNotEquals("case.category","CR"))
or.add(new Where().addEquals("case.hearings.type","ARR"))
where.add(or)

logger.info("$SCRIPT: Querying ...")
def dispositions = DomainObject.findBy("CaseDisposition", where,sel("id"))
logger.info("$SCRIPT: Found ${dispositions.size()} CaseDispositions with blank cf_beginDate.")
/**** END QUERY */


/**** PROCESS */
logger.info("$SCRIPT: Beginning processing ...")
done = 0
dispositions.each { cdId ->
  if (done % 100 == 0) logger.info("$SCRIPT: $done CaseDispositions updated.")
  CaseDisposition cd = CaseCore.get("CaseDisposition",cdId)
  Case c = cd.getCase()

  if ((c.category == 'CR' || c.caseType == 'CR') && c.caseJurisdiction == 'GEN') {
    arraignment = c.hearings.findAll { it.resultDate && it.resultType && it.resultType in LookupList.get("EVENT_RESULT").items?.findAll { li ->
        li.attributes?.find { it.attributeType == 'SCR' && it.name == 'A' }
      }?.collect { it.code }
    }?.min { it.resultDate }
    if (arraignment) {
      withTx { 
        cd.cf_beginDate = arraignment.resultDate
        cd.saveOrUpdate()
      }
      if(_debug) logger.info("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - set cf_beginDate = ${cd.cf_beginDate?.format('MM/dd/yyyy')} from arraignment ${arraignment.entityShortNameAndId}")
      fixed << "CF_BEGINDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_beginDate?.format('MM-dd-yyyy')}"
    }
    else {
      if(_debug) logger.warn("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - No valid arraignment found on the case. Manual review required.")
      cpcCrims++
    }
  }

  else if (c.caseJurisdiction == 'DOM') {
    lastDisp = c.dispositions.findAll { it.dispositionDate < cd.reopenReasonDate }?.max { it.dispositionDate }
    if (!lastDisp || lastDisp.dispositionType != 'B12') {
      withTx { 
        cd.cf_beginDate = cd.reopenReasonDate
        cd.saveOrUpdate()
      }
      if(_debug) logger.info("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - set cf_beginDate = ${cd.cf_beginDate?.format('MM/dd/yyyy')}")
      fixed << "CF_BEGINDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_beginDate?.format('MM-dd-yyyy')}"
    } else {
      withTx { 
        cd.cf_beginDate = lastDisp.cf_beginDate
        cd.saveOrUpdate()
      }
      if(_debug) logger.info("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - set cf_beginDate = ${cd.cf_beginDate?.format('MM/dd/yyyy')}")
      fixed << "CF_BEGINDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_beginDate?.format('MM-dd-yyyy')}"
    }
  }

  else {
    withTx { 
      cd.cf_beginDate = c.filingDate
      cd.saveOrUpdate()
    }
    if(_debug) logger.info("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - set cf_beginDate = ${cd.cf_beginDate?.format('MM/dd/yyyy')}")
    fixed << "CF_BEGINDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_beginDate?.format('MM-dd-yyyy')}"
  }

  DomainObject.flushSession()
  DomainObject.clearSession()
  done++
} // end loop
/**** END PROCESS */
if (!fixed) logger.warn("$SCRIPT: No case dispositions were fixed in this run")
logger.info("$SCRIPT: Fixed ${fixed.size()}. Flagged ${flagged.size()} for review. $cpcCrims CPC criminal case dispositions were not updated because no arraignment was found.")
if (flagged) logger.info("$SCRIPT: Flagged items: " + "\n" + flagged.join("\n"))