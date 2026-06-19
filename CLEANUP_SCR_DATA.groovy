/*

CLEANUP_SCR_DATA
SC Reports Data Cleanup Script
Cleans up converted data to ensure compatibility with SC Reports

-- META --
Author: J Reynolds
Date:   Jun 2026
Jira #: MCOE-2635

-- INPUT --
_maxUpdates  {Integer} The max number of updates to perform per entity (default: 10000)
_debug       {Boolean} Print items to log

-- OUTPUT --
fixed    {List} Items that were updated by the script.
flagged  {List} Items that need manual review

*/

final SCRIPT = "CLEANUP_SCR_DATA"
logger.info("$SCRIPT: Starting ...")
//_maxUpdates ?= 10000
fixed = []
flagged = []
cpcCrims = 0

/**** QUERY */
logger.info("$SCRIPT: Building query ...")
Where where = new Where()
where.addIsNull("cf_reopenDate")
where.addGreaterThan("case.filingDate",DateUtil.parse("01/01/2010"))
where.addNotLike("case.caseType","SUB%")
where.addNotLike("case.status", "%NONSC%")
where.addGreaterThan("dispositionDate",DateUtil.parse("01/01/2023"))

Or or = new Or()
or.add(new Where().addNotEquals("case.category","CR"))
or.add(new Where().addEquals("case.hearings.type","ARR"))
where.add(or)

logger.info("$SCRIPT: Querying ...")
//def dispositions = DomainObject.findBy("CaseDisposition", where,maxResult(_maxUpdates))
def dispositions = DomainObject.findBy("CaseDisposition", where,sel("id"))
logger.info("$SCRIPT: Found ${dispositions.size()} CaseDispositions with blank cf_reopenDate.")
/**** END QUERY */


/**** PROCESS */
logger.info("$SCRIPT: Beginning processing ...")
done = 0
dispositions.each { cdId ->
  if (done % 100 == 0) logger.info("%SCRIPT: $done CaseDispositions updated.")
  CaseDisposition cd = CaseCore.get("CaseDisposition",cdId)
  Case c = cd.getCase()

  if ((c.category == 'CR' || c.caseType == 'CR') && c.caseJurisdiction == 'GEN') {
    arraignment = c.hearings.findAll { it.resultDate && it.resultType && it.resultType in LookupList.get("EVENT_RESULT").items?.findAll { li ->
        li.attributes?.find { it.attributeType == 'SCR' && it.name == 'A' }
      }?.collect { it.code }
    }?.min { it.resultDate }
    cd.cf_reopenDate = arraignment?.resultDate
    cd.saveOrUpdate()
    if (arraignment) {
      if(_debug) logger.info("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - set cf_reopenDate = ${cd.cf_reopenDate?.format('MM/dd/yyyy')} from arraignment ${arraignment.entityShortNameAndId}")
      fixed << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_reopenDate?.format('MM-dd-yyyy')}"
    }
    else {
      if(_debug) logger.warn("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - No valid arraignment found on the case. Manual review required.")
      // flagged << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')}"
      cpcCrims++
    }
  }

  else if (cd.dispositionType == 'UNDSP') {
    /**** UNDSP: use reopenReasonDate */
    if (cd.reopenReasonDate) {
      if(_debug) logger.info("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - setting cf_reopenDate = ${cd.reopenReasonDate?.format('MM/dd/yyyy')} from reopenReasonDate")
      cd.cf_reopenDate = cd.reopenReasonDate
      cd.saveOrUpdate()
      fixed << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_reopenDate?.format('MM-dd-yyyy')}"
    } else {
      if(_debug) logger.warn("$SCRIPT: ${cd.entityShortNameAndId} on ${c.caseNumber} - UNDSP disposition ${cd.id} has no reopenReasonDate. Manual review required.")
      flagged << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')}"
    }

  } 

  else if (c.filingDate && cd.reopenReasonDate && DateUtil.isSameDay(c.filingDate, cd.reopenReasonDate)) {
      if(_debug) logger.info("$SCRIPT/CLOSED: Case ${c.caseNumber} - setting cf_reopenDate = ${c.filingDate?.format('MM/dd/yyyy')} from case filing date)")
      cd.cf_reopenDate = c.filingDate
      cd.saveOrUpdate()
      fixed << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_reopenDate?.format('MM-dd-yyyy')}"
  }

  else {
    /**** Non-UNDSP: find matching REOPENED status */
    reopenStatus = c.statuses?.find { s ->
      s.value in ['RO','O'] &&
      s.beginDate && cd.reopenReasonDate &&
      DateUtil.isSameDay(s.beginDate, cd.reopenReasonDate)
    }

    if (reopenStatus) {
      if(_debug) logger.info("$SCRIPT/CLOSED: Case ${c.caseNumber} - setting cf_reopenDate = ${reopenStatus.beginDate?.format('MM/dd/yyyy')} from O/RO status")
      cd.cf_reopenDate = reopenStatus.beginDate
      cd.saveOrUpdate()
      fixed << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} = ${cd.cf_reopenDate?.format('MM-dd-yyyy')}"
    } else {
      if(_debug) logger.warn("$SCRIPT/CLOSED/FLAG: Case ${c.caseNumber} - no O/RO status found with beginDate ${cd.dispositionDate?.format('MM/dd/yyyy')} for disposition ${cd.id} (${cd.dispositionType}). Manual review required.")
      flagged << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')}"
    }
  }

  DomainObject.flushSession()
  DomainObject.clearSession()
  done++
} // end loop
/**** END PROCESS */
if ((flagged.size() + cpcCrims) == dispositions.size()) logger.warn("$SCRIPT: No case dispositions were fixed in this run")
logger.info("$SCRIPT: Fixed ${fixed.size()}. Flagged ${flagged.size()} for review. $cpcCrims CPC criminal case dispositions were not updated because no arraignment was found.")
if (flagged) logger.info("$SCRIPT: Flagged items: " + "\n" + flagged.join("\n"))

fixed = []
flagged = []
done = 0

/**** NEXT QUERY */
logger.info("$SCRIPT: Building next query ...")
where = new Where()
where.addIsNull("dateRemoved")
where.addGreaterThan("case.filingDate",DateUtil.parse("01/01/2010"))
where.addNotLike("case.caseType","SUB%")
where.addNotLike("case.status", "%NONSC%")
where.addNotIn("status",["CUR","PENDING","VISIT"])
where.addIn("assignmentRole",["JUD","ADMINJ"])

logger.info("$SCRIPT: Querying ...")
def assignments = DomainObject.findBy("CaseAssignment", where,sel("id"))
logger.info("$SCRIPT: Found ${dispositions.size()} CaseAssignment with blank dateRemoved.")
/**** END QUERY */

assignments.each { caId ->
  if (done % 100 == 0) logger.info("%SCRIPT: $done CaseDispositions updated.")
  CaseAssignment ca = CaseComponent.get("CaseAssignment",caId)
  Case c = ca.getCase()

  nextJudge = c.assignments.findAll { DateUtil.startOfDay(it.dateAssigned) >= DateUtil.startOfDay(ca.dateAssigned) }?.min { it.dateAssigned }
  if (nextJudge) {
    ca.dateRemoved = nextJudge.dateAssigned
    ca.saveOrUpdate()
    if(_debug) logger.warn("$SCRIPT: Case ${c.caseNumber} - judge assignment ${ca.personNameFML} beginning ${ca.dateAssigned?.format('MM/dd/yyyy')} is removed ${ca.dateRemoved?.format('MM/dd/yyyy')}")
    fixed << "JUDGE_DATEREMOVED: ${c.caseNumber} ${ca.personNameFML} ${ca.dateAssigned?.format('MM-dd-yyyy')}"
  } else {
    if(_debug) logger.warn("$SCRIPT: Case ${c.caseNumber} - no subsequent judge found for judge assignment ${ca.personNameFML} beginning ${ca.dateAssigned?.format('MM/dd/yyyy')}. Manual review required.")
    flagged << "JUDGE_DATEREMOVED: ${c.caseNumber} ${ca.personNameFML} ${ca.dateAssigned?.format('MM-dd-yyyy')}"
  }

  DomainObject.flushSession()
  DomainObject.clearSession()
  done++
}

logger.info("$SCRIPT: Fixed ${fixed.size()}. Flagged ${flagged.size()} for review. $cpcCrims CPC criminal case dispositions were not updated because no arraignment was found.")
if (flagged) logger.info("$SCRIPT: Flagged items: " + "\n" + flagged.join("\n"))
