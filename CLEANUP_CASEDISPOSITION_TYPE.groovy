/*

CLEANUP_CASEDISPOSITION_TYPE
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
currentDispositionTypes = LookupList.get("CASE_DISPOSITION").items.collect { it.code }
fixed = []
flagged = []

/**** QUERY */
logger.info("$SCRIPT: Building query ...")
Where where = new Where()
where.addNotIn("dispositionType",currentDispositionTypes)

logger.info("$SCRIPT: Querying ...")
def dispositions = DomainObject.findBy("CaseDisposition", where,sel("id"))
logger.info("$SCRIPT: Found ${dispositions.size()} CaseDispositions with invalid dispositionType.")
/**** END QUERY */


/**** PROCESS */
logger.info("$SCRIPT: Beginning processing ...")
done = 0
dispositions.each { cdId ->
  if (done % 100 == 0) logger.info("$SCRIPT: $done CaseDispositions updated so far: fixed ${fixed.size()}, flagged ${flagged.size()} for review. ")
  CaseDisposition cd = CaseCore.get("CaseDisposition",cdId)

  currDisp = cd.dispositionType
  tryDisp = currDisp.replace("/","")
  if(tryDisp in currentDispositionTypes) {
    withTx {
      cd.dispositionType = tryDisp
      cd.saveOrUpdate()
    }
    if (_debug) logger.debug("$SCRIPT: ${cd.entityShortNameAndId} on ${cd.getCase().caseNumber} updated to ${cd.dispositionType}")
    fixed << "DISPOSITIONTYPE: ${cd.getCase().caseNumber}/${cd.dispositionType}"
  } else {
    if (_debug) logger.debug("$SCRIPT: ${cd.entityShortNameAndId} on ${cd.getCase().caseNumber} no match found for ${cd.dispositionType}")
    flagged << "DISPOSITIONTYPE: ${cd.getCase().caseNumber} - ${cd.dispositionType} is not valid"
  }

  DomainObject.flushSession()
  DomainObject.clearSession()
  done++
} // end loop
/**** END PROCESS */
logger.info("$SCRIPT: Fixed ${fixed.size()}. Flagged ${flagged.size()} for review.")
if (flagged) logger.info("$SCRIPT: Flagged items: " + "\n" + flagged.join("\n"))