/*

ADD_CASEDISP_JUDGE
Add Case Disposition on Judge Assignment
Adds a case disposition when a judge is assigned (for SC reporting / tracking)

-- RULE INPUTS --
_ca      *{CaseAssignment} Case assignment of the judge

-- VERSION 1 META --
Author:   J Reynolds
Date:     June 2026
Project:  Montgomery

*/

if (_ca.case.caseJurisdiction == 'PROB') return

_ca.dateAssigned ?= _ca.statusDate ?: new Date()
_ca.saveOrUpdate()

lastDisp = _ca.case.dispositions.findAll { 
  // Not undisposed
  it.dispositionType != 'UNDSP' && 
    // If DR, the "last" disposition only counts if it was a transfer - otherwise we treat this as a new motion
    (_ca.case.caseJurisdiction != 'DOM' || it.dispositionType == 'B12') 
}?.max { it.dispositionDate }
String actionCode = lastDisp?.dispositionSource

CaseDisposition newDisp = _ca.case.dispositions.find { it.dispositionType == 'UNDSP' } ?: new CaseDisposition()
newDisp.dispositionDate ?= _ca.dateAssigned ?: _ca.statusDate ?: new Date()
newDisp.dispositionType ?= 'UNDSP'
newDisp.reopenReasonDate ?= newDisp.dispositionDate
newDisp.reopenReason ?= 'OPEN'

// If there is a previous disposition, transfer the reporting info from it
if (lastDisp) {
  newDisp.cf_beginDate ?= lastDisp.cf_beginDate
  if (lastDisp.cf_beginDate) newDisp.cf_reopenDate ?= newDisp.reopenReasonDate
  // Carry over the action code from the previous disposition
  newDisp.dispositionSource ?= lastDisp.dispositionSource
} 
// Otherwise if it is not a criminal case, and there is no prev disposition (indicating this is the first disposition), add the reporting begin date
else if (!(_ca.case.caseJurisdiction == 'GEN' && (_ca.case.caseType == 'CR' || _ca.case.category == 'CR'))) {
  newDisp.cf_beginDate ?= newDisp.reopenReasonDate
}

_ca.case.add(newDisp)
newDisp.saveOrUpdate()



