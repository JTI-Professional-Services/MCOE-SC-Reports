import com.sustain.reports.ReportException
/** Report Metadata Block moved to end **/

/**** DECLARE GLOBALS */
_reportLetter ?= _report.tokenize(' ')[1]
final SCRIPT = "SUPREME_COURT_REPORT_" + _reportLetter
logger.info("$SCRIPT: Initializing data source generator...")
final String ROOT_ENTITY = "CaseDisposition"
final String ROOT_ENTITY_PLURAL = "CaseDispositions"
_data = []
/**** END GLOBALS */

/**** PARSE INPUTS */
logger.info("$SCRIPT: Parsing inputs...")

final REPORT_BEGIN = DateUtil.parse("${_reportMonth}/1/${_reportYear}")
final REPORT_END = DateUtil.endOfDay(DateUtil.getLastDayOfMonth(REPORT_BEGIN))
logger.info("$SCRIPT: Reporting period: ${REPORT_BEGIN?.format('MM/dd/yyyy')} to ${REPORT_END?.format('MM/dd/yyyy')}")
_cutoff ?= DateUtil.parse("01/01/2010")

// Throw an exception if the reporting period isn't fully in the past
// if(!DateUtil.lt(REPORT_END,new Date())) {
//   throw new ReportException("Can't generate report for this period until after ${REPORT_END?.format('MM/dd/yyyy')}")
// }

// Throw an exception if the dirperson can't be found (this should NEVER EVER happen when using the judge selector!!!)
reportingJudge = DirPerson.getByCode(_judge.tokenize(' ')?.last())
if (!reportingJudge) {
  throw new ReportException("Couldn't find a DirPerson matching ${_judge}")
}

final JUDGE_NAME = reportingJudge?.fml
final JUDGE_ID = reportingJudge?.id
final JUDGE_NUMBER = reportingJudge?.findIdentificationByType('BAR')?.identificationNumber ?: ""
final EMAIL = SystemProperty.getValue((String)"reports.form.${_reportLetter.toLowerCase()}.notification.email") ?: "System Property reports.form.${_reportLetter.toLowerCase()}.notification.email not set"
final MAP_REMOVED = _caseDetails.containsIgnoreCase("removed")
final LIMIT_ROWS = _rows.tokenize(',')
final LIMIT_COLUMNS = _columns.tokenize(',')

formList = LookupList.get("SCR_FORM_" + _reportLetter)
if (!formList) {
  throw new ReportException("Lookup List SCR_FORM_${_reportLetter} not found")
}
/**** END PARSE INPUTS */

Map mapStarter = [
    reportBegin     : REPORT_BEGIN,
    reportEnd       : REPORT_END,
    judgeName       : JUDGE_NAME,
    judgeBarNumber  : JUDGE_NUMBER,
    email           : EMAIL,
    caseCount       : 0,
    pendingValue    : 0,
  ]

/**** CACHE LOOKUPS */

  logger.info("$SCRIPT: Caching lookup lists...")

  // Cache the SCR values for the lookup lists as a Map of "code: attribute value" pairs

  // For reportable arraignments, the map is "event.resultType: event.type"
  final REPORTABLE_ARRAIGNMENTS = LookupList.get("EVENT_RESULT").items?.findAll { li ->
    li.attributes?.find { attr ->
      attr.attributeType == 'SCR' &&
      attr.name == 'A'
    }
  }?.collectEntries { li ->
    [
      (li.code): li.attributes?.find { attr ->
        attr.attributeType == 'SCR' &&
        attr.name == 'A' }?.value
    ]
  }

  if(_reportLetter == 'A' && !REPORTABLE_ARRAIGNMENTS) {
    throw new ReportException("No SCR ${_reportLetter} attributes are defined for Lookup List EVENT_RESULT.")
  }
  if (_debug && _reportLetter == 'A') logger.debug("$SCRIPT: ${REPORTABLE_ARRAIGNMENTS.size()} reportable events: ${REPORTABLE_ARRAIGNMENTS.toString()}")

  // For subcase types, the map is "lookup code: column letter"
  final SUB_CASE_TYPES = LookupList.get("SUB_CASE_TYPE").items?.findAll { li ->
    li.attributes?.find { attr ->
      attr.attributeType == 'SCR' &&
      (attr.name == _reportLetter ||
        (_reportLetter in ['AJ','IJ'] && attr.name == 'J'))
    }
  }?.collectEntries { li ->
    [
      (li.code): li.attributes?.find { attr ->
        attr.attributeType == 'SCR' &&
        (attr.name == _reportLetter ||
          (_reportLetter in ['AJ','IJ'] && attr.name == 'J'))}?.value
    ]
  }
  if (!SUB_CASE_TYPES) {
    throw new ReportException("No SCR ${_reportLetter} attributes are defined for Lookup List SUB_CASE_TYPE.")
  }
  if (_debug) logger.debug("$SCRIPT: ${SUB_CASE_TYPES.size()} subcase types: ${SUB_CASE_TYPES.keySet().join(', ')}")

  // For case dispositions, the map is "lookup code: row number (as a string)"
  final CASE_DISPOSITIONS = LookupList.get("CASE_DISPOSITION").items?.findAll { li ->
    li.attributes?.find { attr ->
      attr.attributeType == 'SCR' &&
      (attr.name == _reportLetter ||
        (_reportLetter in ['AJ','IJ'] && attr.name == 'J')) &&
      attr.value != 'TIME'
    }
  }?.collectEntries { li ->
    [
      (li.code): li.attributes?.find { attr ->
        attr.attributeType == 'SCR' &&
        (attr.name == _reportLetter ||
          (_reportLetter in ['AJ','IJ'] && attr.name == 'J')) &&
        attr.value != 'TIME'
      }?.value
    ]
  }
  // We should have at least one case disposition
  if (!CASE_DISPOSITIONS) {
    throw new ReportException("No SCR ${_reportLetter} attributes are defined for Lookup List CASE_DISPOSITION.")
  }
  if (_debug) logger.debug("$SCRIPT: ${CASE_DISPOSITIONS.size()} case disposition types: ${CASE_DISPOSITIONS.keySet().join(', ')}")

  // For inactive dispositions, we just need a list of the dispos that are considered inactive for purposes of case age calculations
  final INACTIVE_DISPOSITIONS = LookupList.get("CASE_DISPOSITION").items?.findAll { li ->
    li.attributes?.find { attr ->
      attr.attributeType == 'SCR' &&
      (attr.name == _reportLetter ||
        (_reportLetter in ['AJ','IJ'] && attr.name == 'J')) &&
      attr.value == 'TIME'
    }
  }?.collect { it.code }
  if (_debug) logger.debug("$SCRIPT: ${INACTIVE_DISPOSITIONS.size()} inactive disposition types: ${INACTIVE_DISPOSITIONS.join(', ')}")

  // Retrieve & cache the columns and rows for the requested report; Rows are letter codes, columns are number codes
  logger.info("$SCRIPT: Retrieving metadata for Form ${_reportLetter}")

  // Cache column data as a List of Maps (improves performance over leaving the DB connection open)
  final COLUMNS = formList.items.findAll { !it.code.isNumber() }.collect {
    [
      index: (it.code.toCharacter() as Integer) - 64,
      code: it.code,
      label: it.label,
      description: it.description,
    ]
  }.sort { it.index }
  if (!COLUMNS) {
    throw new ReportException("Couldn't find any column definitions in Lookup List SCR_FORM_${_reportLetter}.")
  }

  final ROWS    = formList.items.findAll { it.code.isNumber() }.collect {
    idx = NumberUtil.parseInt(it.code)
    if (idx == null || !(idx instanceof Integer)) {
      throw new ReportException("Bad rowdef '${it.code}' in Lookup List SCR_FORM_${_reportLetter}.")
    }
    return [
      index: idx,
      code: it.code,
      label: it.label,
      description: it.description,
    ]
  }.sort { it.index }
  if (!ROWS) {
    throw new ReportException("Couldn't find any row definitions in Lookup List SCR_FORM_${_reportLetter}.")
  }

  if (_debug) logger.debug("$SCRIPT: Retrieved metadata. Columns: ${COLUMNS.collect{it.code}.join(', ')}; Rows: ${ROWS.collect{it.code}.join(', ')}")

  // Unclog the database connection since we've cached everything we need up to this point
  DomainObject.flushSession()
  DomainObject.clearSession()
/**** END LOOKUPS */


/**** QUERY DATABASE */
  logger.info("$SCRIPT: Building query...")

  /** Query:
   Parent case filed before end of period
   Parent case filed on or after the cutoff date
   Judge is assigned to the case at some point - we will narrow this later
   Parent case status isn't "Open - Non-SCR"
   Parent case type isn't a submission type
   Parent case has at least one subcase with a valid reporting type
   For Report B: CD must have a valid disposition source
   CD started on or before end of period
   CD end is null, UNDSP, or on/after report begin
  */

  Where where = new Where()
  where.addLessThan("case.filingDate", REPORT_END)
  where.addGreaterThanOrEquals("case.filingDate", _cutoff)
  where.addIn("case.assignments.directoryPerson.id", JUDGE_ID)
  where.addNotLike("case.status", "%NONSC%")
  where.addNotLike("case.caseType", "SUB%")
  where.addContainsAny("case.subCases.subCaseType", SUB_CASE_TYPES.keySet())

  if (_reportLetter == 'B') where.addContainsAny("dispositionSource", SUB_CASE_TYPES.keySet())

  where.addIsNotNull("cf_beginDate")
  where.addLessThanOrEquals("cf_beginDate", REPORT_END)

  Or orReopen = new Or()
  orReopen.add(new Where().addIsNull("cf_reopenDate"))
  orReopen.add(new Where().addLessThanOrEquals("cf_reopenDate",REPORT_END))
  where.add(orReopen)

  Or orEnd = new Or()
  orEnd.add(new Where().addEquals("dispositionType", "UNDSP"))
  orEnd.add(new Where().addGreaterThanOrEquals("dispositionDate", REPORT_BEGIN))
  where.add(orEnd)

  logger.info("$SCRIPT: Querying database...")
  def rootList = DomainObject.findBy(ROOT_ENTITY, where, sel("id"))
  logger.info("$SCRIPT: Got ${rootList.size()} initial $ROOT_ENTITY_PLURAL.")
/**** END QUERY */


/**** REPORT PROCESSING */


/**
 * REPORT PROCESSING OUTLINE
 *
 * PRE-FLIGHT CHECK: JUDGE
 * CD-LEVEL REPORTABILITY CHECK
 * BEGIN DATE LOGIC
 * REPORTING CATEGORY
 * CALCULATE CASE AGE
 * ROW PLACEMENT: PENDING
 * TERMINATIONS
 * Unclog the DomainObject cache
*/

// Note that we do NOT exit early if there is no data, because we still need to cross-fill the report.
logger.info("$SCRIPT: Processing data...")

rootList.each { cdId ->
  CaseDisposition cd = CaseComponent.get("CaseDisposition",cdId)
  Case c = cd.getCase()

/**** PRE-FLIGHT CHECKS */
  if(_debug) logger.debug("$SCRIPT: Checking to see if ${c.caseNumber} is reportable...")

  // Is the disposition type reportable?
  if (cd.dispositionType != 'UNDSP' && cd.dispositionType !in CASE_DISPOSITIONS.keySet()) {
    if (MAP_REMOVED) {
      _data << mapStarter.clone() + [
        caseNumber      : c.caseNumber,
        actionType      : "${cd.dispositionType} is not a reportable disposition type",
        rowIndex        : 99
      ]
    }
    if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} - ${cd.dispositionType} is not a reportable disposition type")
    return
  }

  // For report B, is the action code reportable?
  if (_reportLetter == 'B' && cd.dispositionSource !in SUB_CASE_TYPES.keySet()) {
    if (MAP_REMOVED) {
      _data << mapStarter.clone() + [
        caseNumber      : c.caseNumber,
        actionType      : "${cd.dispositionSource} is not a reportable action code",
        rowIndex        : 99
      ]
    }
    if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} - ${cd.dispositionSource} is not a reportable action code")
    return
  }

  /* NOTE as of 6/10/26 the judge check was expanded to clarify removal reasons */

  // Is the reporting judge assigned for the duration of the disposition?
    // Get ALL assignments for this judge that have role 'JUD' or 'ADMINJ'
  assignmentList = c.assignments.findAll { ca ->
      ca.directoryPerson?.id == JUDGE_ID &&
      ca.assignmentRole in ['JUD','ADMINJ']
    }

  // If the list is empty, throw this out
  if (!assignmentList) {
    errMsg = "$JUDGE_NAME was never assigned to the case with role JUD or ADMINJ"
    if (MAP_REMOVED) {
      _data << mapStarter.clone() + [
        caseNumber      : c.caseNumber,
        actionType      : errMsg,
        rowIndex        : 99
      ]
    }
    if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} - $errMsg")
    return
  }
  
  Boolean isValid = false
  errMsg = []

  if(_debug) logger.debug("$SCRIPT: Checking judge assignments...")
  for (assignment in assignmentList) {
    // If the judge assignment is current and assignment date is on or before the SCR start date, then this is reportable
    if (assignment.status == 'CUR' && DateUtil.startOfDay(assignment.dateAssigned) <= DateUtil.startOfDay(cd.cf_reopenDate ?: cd.cf_beginDate)) {
      isValid = true
      break
    }
    
    // If the judge assignment status is Pending or Visiting, not valid
    else if (assignment.status in ['PENDING','VISIT']) {
      errMsg << "Assignment beginning ${assignment.dateAssigned?.format('MM/dd/yyyy')} has status ${assignment.status}"
    }

    // If the judge assignment date is after the SCR start date, not valid
    else if (DateUtil.startOfDay(assignment.dateAssigned) > DateUtil.startOfDay(cd.cf_reopenDate ?: cd.cf_beginDate)) {
      errMsg << "Disposition began on ${(cd.cf_reopenDate ?: cd.cf_beginDate)?.format('MM/dd/yyyy')} but the judge was assigned to the case on ${assignment.dateAssigned?.format('MM/dd/yyyy')} (${DateUtil.diffInDays(DateUtil.startOfDay(assignment.dateAssigned), DateUtil.startOfDay(cd.cf_reopenDate ?: cd.cf_beginDate))} day(s) later)"
    }
    
    // if the judge has no removal date, not valid
    else if (!assignment.dateRemoved) {
      errMsg << "Assignment beginning ${assignment.dateAssigned?.format('MM/dd/yyyy')} has status ${assignment.status} but no removal date"
    }
    
    // If the judge removal date is before the disposition date (or current date if UNDSP), not valid
    else if (DateUtil.startOfDay(assignment.dateRemoved) < DateUtil.startOfDay(getEndDate(cd) ?: new Date())) {
      if (getEndDate(cd)) errMsg << "Disposition date is ${cd.dispositionDate?.format('MM/dd/yyyy')} but the judge was removed from the case on ${assignment.dateRemoved?.format('MM/dd/yyyy')} (${DateUtil.diffInDays(DateUtil.startOfDay(cd.dispositionDate), DateUtil.startOfDay(assignment.dateRemoved))} day(s) earlier)"
      else errMsg << "Assignment ended on ${assignment.dateRemoved?.format('MM/dd/yyyy')} but the disposition type is UNDISPOSED"
    }

    // If we made it past all the other checks, then this is reportable
    else {
      isValid = true
      break
    }
  } // end for loop

  if (!isValid) {
    if (MAP_REMOVED) {
      _data << mapStarter.clone() + [
        caseNumber      : c.caseNumber,
        actionType      : errMsg.join("\n"),
        rowIndex        : 99
      ]
    }
    if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} - ${errMsg.join('; ')}")
    return
  }
/**** END PRE-FLIGHT CHECKS */


  logger.info("$SCRIPT: Initializing case map...")
  Map map = mapStarter.clone() + [
      caseNumber      : c.caseNumber,
      caseCount       : 1,
      beginDate       : cd.cf_beginDate,
      reopenDate      : cd.cf_reopenDate,
    ]

/**** VISITING JUDGE */
  // Visiting judge just needs to intersect disposition period
  visitingJudge = c.assignments.find { ca ->
    ca.assignmentRole in ["VJ", "VJR"] &&
    DateUtil.intervalsIntersect(cd.cf_reopenDate ?: cd.cf_beginDate, getEndDate(cd) ?: new Date(), ca.dateAssigned, ca.dateRemoved ?: new Date())
  }
  if (visitingJudge) {
    map.visitingJudge = "${visitingJudge.assignmentRole} - ${visitingJudge.fml}"
    if (_debug) logger.debug("$SCRIPT: Visiting judge ${visitingJudge.fml}")
  }
/**** END VISITING JUDGE */


/**** REPORTING CATEGORY */
  map.caseType = c.category in SUB_CASE_TYPES.keySet() ? c.category : c.subCases?.find{ it.subCaseType in SUB_CASE_TYPES }?.subCaseType
  if (!map.caseType && _reportLetter != 'B') throw new ReportException("Error on ${c.caseNumber} - caseType not valid. Tried Case.category ${c.category} and subcase type ${c.subCases?.find{ it.subCaseType in SUB_CASE_TYPES }?.subCaseType}")

  map.actionType = _reportLetter == 'B' ? cd.dispositionSource : map.caseType
  if (_debug) logger.debug("$SCRIPT: Reporting category ${map.actionType}, begin date ${map.beginDate?.format('MM/dd/yyyy')}")


  if (!SUB_CASE_TYPES[map.actionType]) throw new ReportException("Error on ${c.caseNumber} - Column code ${SUB_CASE_TYPES[map.actionType]} was not found in SCR_FORM_${_reportLetter} (SUB_CASE_TYPE ${map.actionType}) - does the SCR attribute have a Value?")

  // If the visiting judge type is "responsible" (not just assisting) then report this only in column V
  if (visitingJudge?.assignmentRole == 'VJR') { column = COLUMNS.find {it.code == 'V'} }
  else { column = COLUMNS.find {it.code == SUB_CASE_TYPES[map.actionType]} }

  if (!column) throw new ReportException("Error on ${c.caseNumber} - Column code ${SUB_CASE_TYPES[map.actionType]} was not found in SCR_FORM_${_reportLetter} (SUB_CASE_TYPE ${map.actionType}) - does the SCR attribute have a Value?")

  map.columnLetter = column.code
  map.columnIndex = column.index
  map.columnLabel = column.label

  if (_debug) logger.debug("$SCRIPT: Beginning of period column ${map.columnLetter}")
/**** END REPORTING CATEGORY */


/**** ROW PLACEMENT: PENDING */

  // Find the status at the beginning of the period
  beginningRow = null
  // If the begin date is on or after the beginning of the reporting period, then it's Newly Filed
  if (cd.cf_beginDate >= REPORT_BEGIN) beginningRow = '2'
  // If the reopen date exists and is within the period, then it was reopened in the period
  else if (cd.cf_reopenDate && cd.cf_reopenDate >= REPORT_BEGIN) {
    beginningRow = '3'
  }
  // Otherwise, the case was pending at the beginning of the period
  else beginningRow = '1'

  row = ROWS.find { it.code == beginningRow }
  if (!row) throw new ReportException("Report metadata error - Definition for pending row ${beginningRow} was not found in SCR_FORM_${_reportLetter}")

  map.rowIndex = row.index
  map.rowNumber = row.code
  map.rowLabel = row.label
  map.pendingValue = 1

  if((!LIMIT_COLUMNS || map.columnLetter in LIMIT_COLUMNS) && (!LIMIT_ROWS || map.rowNumber in LIMIT_ROWS)) _data << map.clone()
  if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} added to column ${map.columnLetter}, row ${map.rowNumber}")

  // Clear the reopen date & case age info before emitting any termination row
  map.reopenDate = null
  if (map.caseDue) {
    map.guidelineMonths = null
    map.caseDue = null
    map.caseAgeGross = null
    map.caseAgeNet = null
    map.monthsInactive = null
    map.monthsOverdue = null
  }
/**** END ROW PLACEMENT PENDING */


/**** CALCULATE CASE AGE */
  if (map.columnLetter !in ['T','V'] && (getEndDate(cd) ?: new Date()) > REPORT_END) {
    gl = column.description?.tokenize(' ')?.last()
    map.guidelineMonths = gl.isNumber() ? NumberUtil.parseInt(gl) : null
    
    if (!map.guidelineMonths) throw new ReportException("Report metadata error - Column ${column.code} doesn't have a guideline defined in SCR_FORM_${_reportLetter}")
    
    caseAgeData = getCaseAge(map.reopenDate ?: map.beginDate, REPORT_END, map.guidelineMonths)
    map.caseDue = caseAgeData[0]
    map.caseAgeGross = caseAgeData[1]
    map.monthsInactive = caseAgeData[2]
    if (map.caseDue) {
      map.caseAgeNet = map.caseAgeGross - map.monthsInactive
      map.monthsOverdue = map.caseAgeNet - map.guidelineMonths
    } else {
      map.guidelineMonths = null
    }
  }
/**** END CASE AGE */


/**** TERMINATION */

  if (cd.dispositionType in CASE_DISPOSITIONS.keySet() && 
      cd.dispositionDate &&
      DateUtil.isInRange(cd.dispositionDate, REPORT_BEGIN, REPORT_END) ) {
    if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} terminated on ${c.dispositionDate?.format('MM/dd/yyyy')} with reason ${cd.dispositionType} - ${LookupItem.getLabel('CASE_DISPOSITION',cd.dispositionType)}")
    
    map.dispositionType = cd.dispositionType
    map.dispositionDate = cd.dispositionDate

    row = ROWS.find { it.code == CASE_DISPOSITIONS[map.dispositionType] }
    if (!row) throw new ReportException("Error on ${c.caseNumber} - Definition for row ${CASE_DISPOSITIONS[map.dispositionType]} was not found in SCR_FORM_${_reportLetter} (CASE_DISPOSITION ${map.dispositionType}) - does the SCR attribute have a Value for this report?")
    map.rowIndex = row.index
    map.rowNumber = row.code
    map.rowLabel = row.label
    map.pendingValue = -1

    if((!LIMIT_COLUMNS || map.columnLetter in LIMIT_COLUMNS) && (!LIMIT_ROWS || map.rowNumber in LIMIT_ROWS)) _data << map.clone()
    if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} added to column ${map.columnLetter}, row ${map.rowNumber}")
  } else {
    if(_debug) logger.debug("$SCRIPT: ${c.caseNumber} ${cd.entityShortNameAndId} had no reportable termination")
  }
/**** END TERMINATION */


  // Unclog the cache
  DomainObject.flushSession()
  DomainObject.clearSession()
} // end loop

logger.info("$SCRIPT: Mapped ${_data.size()} records")


/**** CROSS FILL */
logger.info("$SCRIPT: Filling in total rows/columns and empty cells...")
COLUMNS.findAll { !LIMIT_COLUMNS || it.code in LIMIT_COLUMNS }?.each { col ->
  if(_debug) logger.debug("$SCRIPT: Starting cross fill on column ${col.code}")
  ROWS.each { row ->
    if (_debug) logger.debug("$SCRIPT: Row ${row.code} | ${row.description}")
    // Don't crossfill if this cell already has something in it
    if (_data.find { it.rowNumber == row.code && it.columnLetter == col.code }) {
      //if(_debug) logger.debug("$SCRIPT: Data already exists for row ${row.code}")
      return
    }
    Map map = mapStarter.clone() + [
      rowIndex        : row.index,
      rowNumber       : row.code,
      rowLabel        : row.label,
      columnIndex     : col.index,
      columnLetter    : col.code,
      columnLabel     : col.label,
    ]

    // Find the index of the termination total row so we can correctly order the later rows
    Integer t = ROWS.find { it.description?.containsIgnoreCase("term total") }?.index ?: 19
    switch(map.rowIndex) {
      case (1..3):
        map.pendingValue = 1
      case (5..t-1):
        map.pendingValue = -1
    }
    if(_debug) logger.debug("$SCRIPT: Crossfilling row ${map.rowIndex}")


    // Column total
    if (col.code == "T" && !row.description?.containsIgnoreCase("highest overdue")) {
      map.caseCount = _data.sum { (it.rowNumber == row.code && (it.caseNumber || row.description)) ? it.caseCount : 0 }
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount} total across all columns for row ${row.code}")
      if (row.description?.containsIgnoreCase("overdue")) {
        map.rowIndex++
        if(_debug) logger.debug("$SCRIPT: Incremented row index for ${row.code} to make some space for later")
      }
    }

    // Visiting judge column
    else if (col.code == "V" && !row.description?.containsIgnoreCase("overdue")) {
      map.caseCount = _data.sum { (it.rowNumber == row.code && it.visitingJudge && (it.caseNumber /*|| row.description*/) ) ? it.caseCount : 0 }
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount} visiting judges for row ${row.code}")
      if (row.description?.containsIgnoreCase("overdue")) {
        map.rowIndex++
        if(_debug) logger.debug("$SCRIPT: Incremented row index for ${row.code} to make some space for later")
      }
    }

    // Pending total (row 4)
    else if (row.code == "4") {
      map.caseCount = _data.findAll {
        it.columnLetter == col.code &&
        it.caseNumber &&
        it.rowIndex < 4
      }?.sum{ it.caseCount } ?: 0
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount} pending in the period (row ${row.code})")
    }

    // Terminations total (A19,B15,AJ14,IJ17)
    else if (row.description?.containsIgnoreCase("term total")) {
      map.caseCount = _data.findAll {
        it.columnLetter == col.code &&
        it.caseNumber &&
        it.rowIndex > 4 &&
        it.rowIndex < t
      }?.sum{ it.caseCount } ?: 0
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount} terminated in the period (row ${row.code})")
    }

    // Pending end of period (pending - terminations)
    else if (row.description?.containsIgnoreCase("pend end")) {
      map.caseCount = _data.sum { (it.columnLetter == col.code && it.caseNumber && it.rowIndex) ? (it.caseCount * it.pendingValue) : 0 }
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount} pending end of period (row ${row.code})")
    }

    // On Form A, row 23 is hardcoded zero per the Court
    else if (row.description?.containsIgnoreCase("zero")) {
      // Add 1 to the index because we are going to sneak in the "time guideline" label row later
      map.rowIndex++
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount} is a hardcoded value for row ${row.code}")
    }

    // Num cases pending overdue
    else if (row.description?.containsIgnoreCase("pend overdue")) {
      // Add 1 to the index because we are going to sneak in the "time guideline" label row later
      map.rowIndex++
      if(_debug) logger.debug("$SCRIPT: Incremented row index for ${row.code} to make some space for later")
      map.caseCount = _data.findAll {
        it.columnLetter == col.code &&
        it.caseNumber &&
        it.monthsOverdue != null
      }?.size() // Count all the overdue cases for the period
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount}")
    }

    // Num months oldest case is overdue
    else if (row.description?.containsIgnoreCase("highest overdue")) {
      // Add 1 to the index because we are going to sneak in the "time guideline" label row later
      map.rowIndex++
      if(_debug) logger.debug("$SCRIPT: Incremented row index for ${row.code} to make some space for later")

      oldestCase = _data.findAll {
        it.columnIndex == col.index &&
        it.caseNumber
      }?.max{ it.monthsOverdue ?: 0 }
      map.caseCount = oldestCase?.monthsOverdue
      if(_debug) logger.debug("$SCRIPT: ${map.caseCount}")
    }

    _data << map.clone()
    if (_debug) logger.debug("$SCRIPT: Added cell ${map.columnLetter}${map.rowNumber} (${map.caseCount} $ROOT_ENTITY_PLURAL)")
  } // end ROWS

  // Add the time guideline row for this column
  if (_debug) logger.debug("$SCRIPT: Adding time guideline cell for column ${col.code}")

  Map map = mapStarter.clone() + [
    rowIndex        : ROWS.find { it.description?.containsIgnoreCase("pend overdue") }?.index ?: 20,
    rowNumber       : "",
    rowLabel        : "Time Guideline (% in X months)",
    columnIndex     : col.index,
    columnLetter    : col.code,
    columnLabel     : col.label,
    guideline       : col.description,
  ]
  _data << map.clone()

  if (_debug) logger.debug("$SCRIPT: ${map.guideline}")
} // end COLUMNS
logger.info("$SCRIPT: ${_data.size()} dataset records with ${_data.findResults{it.caseNumber}.size()} $ROOT_ENTITY_PLURAL and ${_data.findAll{it.caseCount && !it.caseNumber}.size()} aggregate cells greater than zero")
/**** END CROSS FILL */


/**** DATA SORT */
  // For a crosstab we sort by row then column
  logger.info("$SCRIPT: Sorting data by row > column > begin date > action type")
  _data.sort(true,{ a,b ->
    try {
      a.rowIndex <=> b.rowIndex ?:
      a.columnIndex <=> b.columnIndex ?:
      a.caseType <=> b.caseType ?:
      a.actionType <=> b.actionType ?:
      a.beginDate <=> b.beginDate ?:
      0
    } catch (IllegalArgumentException e) {
      logger.debug("""$SCRIPT: Failed comparing ${a.caseNumber} to ${b.caseNumber}:
        ${a.rowIndex} <=> ${b.rowIndex} ?:
        ${a.columnIndex} <=> ${b.columnIndex} ?:
        ${a.caseType} <=> ${b.caseType} ?:
        ${a.actionType} <=> ${b.actionType} ?:
        ${a.beginDate} <=> ${b.beginDate}
      """)
    }
  })
/**** END SORT */


/**** HELPER FUNCTIONS */
  Date getEndDate(CaseDisposition cd) {
    return (cd?.dispositionType == 'UNDSP') ? null : cd?.dispositionDate
  }

  def getCaseAge(Date caseBegin, Date reportEnd, Integer guideline, def inactiveDispositions=null) {
    Integer gross = DateUtil.diffInDays(reportEnd,caseBegin) as Integer
    Integer inactive = 0 /*inactiveDispositions?.sum {
      end = getEndDate(it) ?: reportEnd
      if (end > reportEnd) end = reportEnd
      begin = it.cf_beginDate ?: caseBegin
      if (begin < caseBegin) begin = caseBegin
      return DateUtil.diffInDays(end,begin) as Integer
    } ?: 0 */
    if (inactive < 0) {
      throw new ArithmeticException("$SCRIPT: Inactive month calculation for ${inactiveDispositions?[0].getCase().caseNumber} was invalid: $inactive")
    }
    if (gross < 0) {
      throw new ArithmeticException("$SCRIPT: Gross case age calculation for ${inactiveDispositions?[0].getCase().caseNumber} was invalid: $gross")
    }
    if (inactive > gross) {
      throw new ArithmeticException("$SCRIPT: Net case age calculation for ${inactiveDispositions?[0].getCase().caseNumber} was invalid: $gross - $inactive = ${gross-inactive}")
    }
    dueDate = DateUtil.addDays(caseBegin, inactive+(guideline*30))
    if (dueDate >= reportEnd) return [null,null,null]
    gross = gross / 30
    inactive = inactive / 30
    return [dueDate, gross, inactive]
  }
/**** END HELPERS */



/*

Report Code: SUPREME_COURT_REPORTS
Report Name: Supreme Court Reports
Report Style: Headless
Report Format: PDF, XLS
Root Entity: CaseDisposition

-- VERSION 3 META --
Author: J Reynolds
Date:   Jun 2026
Jira #: MCOE-2635

-- VERSION 2 META --
Author: J Reynolds
Date:   Mar 2026
Jira #: MCOE-2325

-- VERSION 1 META --
Author: J Reynolds
Date:   Aug 2025
Jira #: MCOE-800

-- PARAMETERS/INPUTS --
_report       *{String} Display name of the report (e.g., "Form A - Common Pleas, General Division")
_reportMonth  *{Integer} [Dropdown Select] Method=User Input; 1,2,3,4,5,6,7,8,9,10,11,12
_reportYear   *{Integer} [Dropdown Select] Method=Query; see BR page for query
_judge        *{String} [Dropdown Select] Method=Query; see BR page for query
_caseDetails  {String} [Dropdown Select] NULL,Show Case Details,Show Case Details + Removed Cases
_columns      {String} Comma delimited letters of column(s) to restrict data to (for testing - non-total columns only)
_rows         {String} Comma delimited numbers of row(s) to restrict data to (non-total rows only)

-- HIDDEN INPUTS --
_cutoff       {Date} Exclude cases filed before this date; defaults to 01/01/2010 if nothing is provided

-- NON-PARAMETER INPUTS (BR ONLY) --
_reportLetter {String} The letter of the report (A, B, AJ, IJ); shortcut for doing an Execute from the BR page
_debug        {Boolean} when true, outputs ALL debug lines

-- RULE OUTPUTS --
_data       {List} Cell-data maps with these fields:
                email             String    System property
                judgeBarNumber    String    Parsed from judge selector
                judgeName         String    Parsed from judge selector
                reportBegin       Date      Parsed from report month/year
                reportEnd         Date      Parsed from report month/year

                caseNumber        String    case.caseNumber
                caseType          String    original case type
                actionType        String    reporting case type (may be different)
                beginDate         Date      date case began to be reportable
                reopenDate        Date      date case was reopened/transferred
                dispositionDate   Date      disposition date (terminations only)
                dispositionType   String    disposition type (terminations only)

                guidelineMonths   Integer   Parsed from report metadata lookup list
                guideline         String    Only used for time guideline row (no data in that row)
                caseDue           Date      case due date, if overdue (null otherwise)
                caseAgeGross      Integer   
                monthsInactive    Integer   
                caseAgeNet        Integer   
                monthsOverdue     Integer   number of months case is overdue, if overdue (null otherwise)
                pendingValue      Integer   1 if pending, -1 if termination, 0 if aggregate
                caseCount         Integer   num cases counted by this row
                visitingJudge     String    name of visiting judge, if any in the period

                rowIndex          Integer   what row to count this in
                rowLabel          String    Display label (to fill the crosstab)
                rowNumber         String    Display number (to fill the crosstab)
                columnIndex       Integer   what column to count this in
                columnLabel       String    Display label
                columnLetter      String    Display letter

*/


