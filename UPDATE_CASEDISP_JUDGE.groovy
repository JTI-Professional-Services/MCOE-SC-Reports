/*

UPDATE_CASEDISP_JUDGE
Update Case Disposition on Judge Transfer
Updates current (UNDSP) case disposition when a judge is removed (for SC reporting / tracking)

-- RULE INPUTS --
_ca      *{CaseAssignment} Case assignment of the judge

-- VERSION 1 META --
Author:		J Reynolds
Date:    	June 2026
Project: 	Montgomery

*/

if (_ca.case.caseJurisdiction == 'PROB') return

lastDisp = _ca.case.dispositions?.find { it.dispositionType == 'UNDSP' }

if (_ca.assignmentRole == 'ADMINJ') lastDisp.dispositionType = 'MCATIJ'
else {
  switch (_ca.case.caseJurisdiction) {
    case "GEN":
    	lastDisp.dispositionType = 'CPCTRANSFR'
    	break
    case "DOM":
    	lastDisp.dispositionType = 'B12'
    	break
    case "MUNI":
    	lastDisp.dispositionType = 'MCITR'
    	break
    default:
      return
  }
}

lastDisp.dispositionDate = new Date()
lastDisp.saveOrUpdate()

_ca.dateRemoved ?= new Date()
_ca.saveOrUpdate()


