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

/**** QUERY - CF_REOPENDATE */
logger.info("$SCRIPT: Building query ...")
Where where = new Where()
where.addIsNull("cf_reopenDate")
where.addIsNotNull("cf_beginDate")

logger.info("$SCRIPT: Querying ...")
def dispositions = DomainObject.findBy("CaseDisposition", where,sel("id"))
logger.info("$SCRIPT: Found ${dispositions.size()} CaseDispositions with non-null cf_beginDate and null cf_reopenDate.")
/**** END QUERY */


/**** PROCESS */
logger.info("$SCRIPT: Beginning processing ...")
done = 0
dispositions.each { cdId ->
  if (done > 0 && done % 100 == 0) logger.info("$SCRIPT: $done CaseDispositions updated.")
  CaseDisposition cd = CaseCore.get("CaseDisposition",cdId)
  Case c = cd.getCase()

  allReportable = c.dispositions.findAll { it.cf_beginDate }

  // If the only reportable disposition on the case is this one, use the begin date
  if (!allReportable.size() == 1 && allReportable[0].id == cd.id) {
    withTx {
      cd.cf_reopenDate = cd.cf_beginDate
      cd.saveOrUpdate()
    }
    done++
    fixed << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} opened on ${cd.cf_reopenDate?.format('MM-dd-yyyy')}"
    return
  }

  // If this disposition is the first reportable one, use the begin date
  if (allReportable.min { it.dispositionDate }.id == cd.id) {
    withTx {
      cd.cf_reopenDate = cd.cf_beginDate
      cd.saveOrUpdate()
    }
    done++
    fixed << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} opened on ${cd.cf_reopenDate?.format('MM-dd-yyyy')}"
    return
  }

  reopenStatus = c.statuses?.find { s ->
      s.value in ['RO','O'] &&
      s.beginDate && cd.reopenReasonDate &&
      DateUtil.isSameDay(s.beginDate, cd.reopenReasonDate)
  }
  
  withTx {
    cd.cf_reopenDate = cd.reopenReasonDate
    cd.saveOrUpdate()
  }
  fixed << "CF_REOPENDATE: ${c.caseNumber}/${cd.dispositionType}/${cd.dispositionDate?.format('MM-dd-yyyy')} reopened on ${cd.cf_reopenDate?.format('MM-dd-yyyy')}"

  DomainObject.flushSession()
  DomainObject.clearSession()
  done++
} // end loop
/**** END PROCESS */

if (fixed.size() == dispositions.size()) logger.warn("$SCRIPT: No case dispositions were fixed in this run")
logger.info("$SCRIPT: Fixed ${fixed.size()}. Flagged ${flagged.size()} for review.")
if (flagged) logger.info("$SCRIPT: Flagged items: " + "\n" + flagged.join("\n"))