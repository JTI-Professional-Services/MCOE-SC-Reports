/** 
 * If something goes wrong when running the report, search the Sustain logs for "SUPREME_COURT_REPORT_C"
 * to find all of this script's debugging output.
 * 
 * Written by Jensen Reynolds
 * 
 */

def cutoff = "01/01/2010" // Disregard cases filed before this date
def caseLimit = 20000 // If the initial query has more than this many cases, the script will stop running
// This is to avoid crashing the environment when we start looping over the case list

/* ----------- Initial setup, declare some enums & variables, etc ------------- */

// Functional data (non-display)
def reportBegin = java.sql.Date.valueOf(java.time.LocalDate.of(_reportYear, ((_reportQuarter-1)*3)+1, 1))
def reportEnd = java.sql.Date.valueOf(java.time.LocalDate.of(_reportYear, _reportQuarter == 4 ? 1 : ((_reportQuarter)*3)+1, 1))
logger.debug("SUPREME_COURT_REPORT_C: Reporting period: " + reportBegin.toString() + " to " + reportEnd.toString())

// DirPerson judge = DirPerson.getByCode(_judge.tokenize(' ').last())
// logger.debug("SUPREME_COURT_REPORT_C: Judge: " + judge.fml)

/* ----------- Collect valid cases for the reporting judge ------------- */
// for Form C, all Probate cases are reported under 1 judge

def sql = """
DECLARE @case_detail_data TABLE 
(
    Case_Number                      nvarchar(100)   NULL,  
    Case_Id                          int             NULL,  
    Case_Category                    nvarchar(20)    NULL,
    SubCase_Type                     nvarchar(20)    NULL,
    Party_Type                       nvarchar(20)    NULL,
    Filing_Date                      date            NULL,
    Run_Date_Case_Status             nvarchar(10)    NULL,
    Run_Date_Status_Date             date            NULL,
    Beginning_Case_Status            nvarchar(10)    NULL,
    Beginning_Status_Date            date            NULL,
    Ending_Case_Status               nvarchar(10)    NULL,
    Ending_Status_Date               date            NULL,
    Primary_Document_Type            nvarchar(50)    NULL,
    Primary_Document_Filing_Date     date            NULL,
    Account_Due_Date                 date            NULL,
    Account_Time_Standard_Code       nvarchar(20)    NULL,
    Case_Notices_Citations           int             NULL,
    Visiting_Judge                   nvarchar(50)    NULL
);

INSERT INTO @case_detail_data
(
    Case_Number,
    Case_Id,
    Case_Category,
    SubCase_Type,
    Party_Type,
    Filing_Date,
    Run_Date_Case_Status,
    Run_Date_Status_Date,
    Beginning_Case_Status,
    Beginning_Status_Date,
    Ending_Case_Status,
    Ending_Status_Date,
    Primary_Document_Type,
    Primary_Document_Filing_Date,
    Account_Due_Date,
    Account_Time_Standard_Code,
    Case_Notices_Citations,
    Visiting_Judge
)

select distinct
  c.caseNumber as Case_Number,
  c.id as Case_Id,
  c.category as Case_Category,
  CASE
    WHEN sub.subCaseType = 'BREC' AND sub.cf_subCaseSubType != 'CORBR' THEN NULL 
    ELSE sub.subCaseType
  END as SubCase_Type,
  p.partyType as Party_Type,
  c.filingDate as Filing_Date,
  c.status as Run_Date_Case_Status,
  c.statusDate as Run_Date_Status_Date,
  bop.value as Beginning_Case_Status,
  bop.beginDate as Beginning_Status_Date,
  eop.value as Ending_Case_Status,
  eop.beginDate as Ending_Status_Date,
  pdoc.number as Primary_Document_Type,
  pdoc.dateFiled as Primary_Document_Filing_Date,
  ts.expireDate as Account_Due_Date,
  ts.code as Account_Time_Standard_Code,
  count(cdoc.id) over(partition by cdoc.case_id) as Case_Notices_Citations,
  vca.code as Visiting_Judge
  
from
  tCase c

  left join tSubCase sub
  on c.id = sub.case_id
  and sub.subCaseType is not null

  left join tParty p
  on c.id = p.case_id
  and p.partyType = 'DEC'

  left join tCaseStatus bop
  on c.id = bop.case_id
  and :reportBegin between bop.beginDate and bop.endDate

  left join tCaseStatus eop
  on c.id = eop.case_id
  and :reportEnd between eop.beginDate and eop.endDate

  left join (
        select 
      SUBSTRING(firstName,1,1) + SUBSTRING(lastName,1,1) + '_' + assignmentRole as code, 
      assignmentRole, 
      dateAssigned, 
      tCaseAssignment.statusDate,
      tCaseAssignment.status, 
      case_id 
        from tCaseAssignment 
    join tPerson on person_id = tPerson.id
    where assignmentRole in ('VJ','VJR')
      and (dateAssigned < :reportEnd OR dateAssigned is null)
      and (tCaseAssignment.status = 'CUR' OR tCaseAssignment.statusDate > :reportBegin)
    ) vca
  on c.id = vca.case_id

  left join (
        select tDocument.id, tDocDef.number, tDocument.case_id, tDocument.dateFiled
        from tDocument 
            join tDocDef on tDocument.docDef_id = tDocDef.id 
        where tDocDef.number in ('PMSC_1.8','PMAR_LICENSE')
        and dateFiled < :reportEnd
    ) pdoc
  on c.id = pdoc.case_id
  
  left join (
        select tDocument.id, tDocDef.number, tDocument.case_id, tDocument.dateFiled
        from tDocument 
            join tDocDef on tDocument.docDef_id = tDocDef.id 
        where tDocDef.number in ('PEST_29.B2','PGRD_29.B2')
        and dateFiled >= :reportBegin and dateFiled < :reportEnd
  ) cdoc
  on c.id = cdoc.case_id

  left join (
    select id, case_id, expireDate, code
    from tTimeStandard
    where category =  'P'
    and code = 'ACCTD'
  ) ts
  on c.id = ts.case_id

where
  c.caseType = 'P' 
  and (
    coalesce(bop.value, c.status) in ('O','RO') 
    OR 
    (eop.value = 'C' AND eop.beginDate >= :reportBegin and eop.beginDate < :reportEnd) 
    OR
    (c.status = 'C' AND c.statusDate >= :reportBegin) 
  )
  and c.filingDate >= :cutoff and c.filingDate < :reportEnd 
;

select *,

  CASE
    WHEN Case_Category = 'EST' and Party_Type = 'DEC' and Filing_Date < :reportBegin THEN 1
    WHEN Case_Category = 'EST' and Party_Type = 'DEC' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 2
    WHEN SubCase_Type = 'GRD2' and Filing_Date < :reportBegin THEN 7
    WHEN SubCase_Type = 'GRD2' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 8
    WHEN SubCase_Type = 'GRD1' and Filing_Date < :reportBegin THEN 11
    WHEN SubCase_Type = 'GRD1' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 12
    WHEN SubCase_Type IN ('GRD4', 'GRD5', 'GRD6') and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 15
    WHEN SubCase_Type = 'GRD3' and Filing_Date < :reportBegin THEN 16
    WHEN SubCase_Type = 'GRD3' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 17
    WHEN SubCase_Type = 'TRS1' and Filing_Date < :reportBegin THEN 20
    WHEN SubCase_Type = 'TRS1' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 21
    WHEN Case_Category = 'MSC' and Filing_Date < :reportBegin THEN 26
    WHEN Case_Category = 'MSC' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 27
    WHEN Case_Category = 'ADP' and Filing_Date < :reportBegin THEN 30
    WHEN Case_Category = 'ADP' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 31
    WHEN Case_Category = 'MI' and Filing_Date < :reportBegin THEN 34
    WHEN Case_Category = 'MI' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 35
    WHEN SubCase_Type = 'EST12' and Filing_Date < :reportBegin THEN 42
    WHEN SubCase_Type = 'EST12' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 43
    WHEN SubCase_Type = 'BREC' and Filing_Date >= :reportBegin and Filing_Date < :reportEnd THEN 46
    WHEN Primary_Document_Type = 'PMSC_1.8' and Primary_Document_Filing_Date >= :reportBegin and Primary_Document_Filing_Date < :reportEnd THEN 48
    WHEN Primary_Document_Type = 'PMAR_LICENSE' and Primary_Document_Filing_Date >= :reportBegin and Primary_Document_Filing_Date < :reportEnd THEN 50
    ELSE 990
  END as Report_Line
from @case_detail_data

UNION ALL

select *,
  CASE
    WHEN Case_Category = 'EST' and Party_Type = 'DEC' THEN 3
    WHEN SubCase_Type = 'GRD2'  THEN 9
    WHEN SubCase_Type = 'GRD1'  THEN 13
    WHEN SubCase_Type = 'GRD3'  THEN 18
    WHEN SubCase_Type = 'TRS1'  THEN 22
    WHEN Case_Category = 'MSC'  THEN 28
    WHEN Case_Category = 'ADP' THEN 32
    WHEN Case_Category = 'MI' THEN 36
    WHEN SubCase_Type = 'EST12'  THEN 44
    WHEN SubCase_Type = 'BREC' THEN 47
    WHEN Primary_Document_Type = 'PMSC_1.8'  THEN 49
    ELSE 991
  END as Report_Line

from @case_detail_data

where 
  Run_Date_Case_Status = 'C' AND Run_Date_Status_Date >= :reportBegin and Run_Date_Status_Date < :reportEnd

UNION ALL

select *,
  CASE
    WHEN Case_Category = 'EST' and Party_Type = 'DEC' THEN 3
    WHEN SubCase_Type = 'GRD2'  THEN 9
    WHEN SubCase_Type = 'GRD1'  THEN 13
    WHEN SubCase_Type = 'GRD3'  THEN 18
    WHEN SubCase_Type = 'TRS1'  THEN 22
    WHEN Case_Category = 'MSC'  THEN 28
    WHEN Case_Category = 'ADP' THEN 32
    WHEN Case_Category = 'MI' THEN 36
    WHEN SubCase_Type = 'EST12'  THEN 44
    WHEN SubCase_Type = 'BREC' THEN 47
    WHEN Primary_Document_Type = 'PMSC_1.8'  THEN 49
    ELSE 992
  END as Report_Line

from @case_detail_data

where 
  Ending_Case_Status = 'C' AND Ending_Status_Date >= :reportBegin and Ending_Status_Date < :reportEnd

order by Report_Line, Case_Number
"""

def cases = DomainObject.querySQL(sql,["reportBegin": reportBegin,"reportEnd": reportEnd, "cutoff": cutoff])
logger.debug("SUPREME_COURT_REPORT_C: Count of cases: " + cases?.size() ?: 0)

// Check case count and abort if we have way too many cases
if ((cases?.size() ?: 0) > caseLimit) { logger.warn("SUPREME_COURT_REPORT_C: More than " + caseLimit + " cases found, aborting"); _data = []; return }

_data = cases?.collect {
  [
    case_number:                    it[0] ?: null,
    case_id:                        it[1] ?: null,
    case_category:                  it[2] ?: null,
    subcase_type:                   it[3] ?: null,
    party_type:                     it[4] ?: null,
    filing_date:                    it[5] ? java.sql.Date.valueOf(it[5]) : null,
    run_date_case_status:           it[6] ?: null,
    run_date_status_date:           it[7] ? java.sql.Date.valueOf(it[7]) : null,
    beginning_case_status:          it[8] ?: null,
    beginning_status_date:          it[9] ? java.sql.Date.valueOf(it[9]) : null,
    ending_case_status:             it[10] ?: null,
    ending_status_date:             it[11] ? java.sql.Date.valueOf(it[11]) : null,
    primary_document_type:          it[12] ?: null,
    primary_document_filing_date:   it[13] ? java.sql.Date.valueOf(it[13]) : null,
    account_due_date:               it[14] ?: null,
    account_time_standard_code:     it[15] ?: null,
    case_notices_citations:         it[16] ?: null,
    visiting_judge:                 it[17] ?: null,
    report_line:                    it[18] ?: null,
  ]
}