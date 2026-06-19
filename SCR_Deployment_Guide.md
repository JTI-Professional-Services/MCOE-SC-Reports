# Supreme Court Reports — Deployment Guide

---

## Overview

The Supreme Court Reports (SCR) are monthly statistical reports submitted by Montgomery County judges to the Ohio Supreme Court. Each judge submits one or more report forms (A, B, AJ, IJ) covering their caseload for a given month. The reports are generated as PDFs or Excel files from within eCourt.

| Form | Description | Original Jira |
|---|---|---|
| Form A | Common Pleas, General Division | MCOE-800 |
| Form B | Common Pleas, Domestic Relations Division | MCOE-873 |
| Form C | Common Pleas, Probate Division | MCOE-892 |
| Form AJ | Administrative Judge | MCOE-1607 |
| Form IJ | Individual Judge | MCOE-1608 |

Forms A, B, AJ, and IJ share a common datasource business rule (`SUPREME_COURT_REPORTS`) driven by lookup list attributes, with a JasperReports template (`SCR_v2.jrxml`) for rendering. Form C (Probate) is a separate report with its own rule and JRXML — it uses a direct SQL query and has no lookup list dependencies. The sections below cover both, with Form C documented separately at the end.

---

## Architecture

| Component | Description |
|---|---|
| `SUPREME_COURT_REPORTS` (BR) | Datasource rule for Forms A/B/AJ/IJ. Queries CaseDispositions, applies judge/period filters, and emits a flat list of cell-data maps for the crosstab. |
| `SCR_v2.jrxml` | JasperReports template for Forms A/B/AJ/IJ. Renders the header, case detail section, and summary crosstab from the BR output. |
| Lookup Lists | Drive the A/B/AJ/IJ report structure — columns, rows, reportable case types, and disposition mappings are all lookup-list-controlled. See [Lookup List Configuration](#lookup-list-configuration) below. |
| System Properties | Supply the notification email address per report form (A/B/AJ/IJ). |
| `SUPREME_COURT_REPORT_C` (BR) | Datasource rule for Form C. Runs a direct SQL query against Probate cases — no lookup list dependencies. |
| `Form_C_Groovy.jrxml` | JasperReports template for Form C. |

---

## Deploy the Business Rule

Deploy `SUPREME_COURT_REPORTS` (from `SCR_V3.groovy`) as a **Datasource** business rule in the eCourt admin UI.

### Report Code and Name

| Field | Value |
|---|---|
| Report Code | `SUPREME_COURT_REPORTS` |
| Report Name | Supreme Court Reports |
| Report Style | Headless |
| Root Entity | `CaseDisposition` |
| Formats | PDF, XLS |

### Input Parameters

| Name | Type | Method | Required | Description |
|---|---|---|---|---|
| `_report` | String | Dropdown — Query | Yes | Display name of the report form (e.g., `"Form A - Common Pleas, General Division"`). The rule extracts the letter from the second token. |
| `_reportMonth` | Integer | Dropdown — Static list `1–12` | Yes | Month of the reporting period. |
| `_reportYear` | Integer | Dropdown — Query | Yes | Year of the reporting period. |
| `_judge` | String | Dropdown — Query | Yes | Judge selector. The rule extracts the DirPerson code from the last token. |
| `_caseDetails` | String | Dropdown — Static | No | One of: *(blank)*, `Show Case Details`, `Show Case Details + Removed Cases`. Controls whether the detail section prints and whether removed cases are shown. |
| `_cutoff` | Date | Hidden | No | Exclude cases filed before this date. Defaults to `01/01/2010` if not provided. |
| `_columns` | String | Hidden / BR page only | No | Comma-delimited column letters to restrict output to (for testing). |
| `_rows` | String | Hidden / BR page only | No | Comma-delimited row numbers to restrict output to (for testing). |

> **BR page only (not on the report UI):**  
> `_reportLetter` — letter shortcut for running directly from the BR page (bypasses the `_report` tokenizing logic).  
> `_debug` — set to `true` to enable verbose debug logging.

---

## Deploy the JRXML

Upload `SCR_v2.jrxml` to the report definition in the eCourt admin UI and associate it with the `SUPREME_COURT_REPORTS` report code.

### JRXML Parameters

After uploading the JRXML, configure the following parameters on the Report Admin page.

| Parameter | Data Type | UI Type | Method | List Source | Default | Hidden | Required | Notes |
|---|---|---|---|---|---|---|---|---|
| `report` | String | Dropdown | User Input List | ¹ | — | No | Yes | |
| `reportMonth` | Integer | Dropdown | User Input List | `1,2,3,4,5,6,7,8,9,10,11,12` | — | No | Yes | |
| `reportYear` | Integer | Dropdown | Query | ² | — | No | Yes | |
| `judge` | String | Dropdown | Query | ³ | — | No | Yes | |
| `caseDetails` | String | Dropdown | User Input List | ⁴ | — | No | No | |
| `lastInventoryDate` | Date | Date Picker | — | — | — | No | No | Passes value directly into report header |
| `cutoff` | Date | Date Picker | — | — | `01/01/2010` | Yes | No | |
| `columns` | String | Multiselect | User Input List | `A,B,C,D,E,F,G,H,I,J,K` | — | Yes | No | See note below |
| `rows` | String | Multiselect | User Input List | `1,2,3,...,23` | — | Yes | No | See note below |
| `jurisdiction` | String | Text Box | — | — | `Montgomery` | Yes | No | |
| `reportLetter` | String | — | — | — | — | Yes | No | |
| `reportTitle` | String | Text Box | — | — | `The Supreme Court of Ohio` | Yes | No | |

> **`columns` and `rows`:** These lists cover the maximum range across all forms — not every form uses all columns or rows. Both must remain **Hidden in production**; unhide temporarily for testing or debugging to restrict output to a specific subset of cells.

**¹ `report` list source:**
- `Form A - Common Pleas General Division`
- `Form B - Domestic Relations Division`
- `Form AJ - Administrative Judge Report`
- `Form IJ - Individual Judge Report`

**⁴ `caseDetails` list source:**
- *(blank)*
- `Show Case Details`
- `Show Case Details + Removed Cases`

**² `reportYear` query:**
```sql
with yearlist as 
(select DATEPART(year, GETDATE())-99 as yr 
union all 
select yl.yr + 1 as yr 
from yearlist yl 
where yl.yr + 1 <= DATEPART(year, GETDATE()))
select yr from yearlist order by yr desc;
```

**³ `judge` query:**
```sql
SELECT distinct dp.firstName + ' ' + dp.lastName + ' ' + de.code
FROM tDirPerson dp 
JOIN tDirEntry de ON de.id = dp.id 
JOIN tDirEntry_parents org ON dp.id = org.children_id
JOIN tDirEntry div ON org.parents_id = div.id
WHERE dp.role in ('ADMINJ', 'JUD') AND de.status = 1
```

---

## Configure System Properties

One System Property is required per report form letter to supply the notification email address printed in the report header.

| Category | Key | Value | Description |
|---|---|---|---|
| reports | `reports.form.a.notification.email` | *(email)* | Notification email for Form A |
| reports | `reports.form.b.notification.email` | *(email)* | Notification email for Form B |
| reports | `reports.form.aj.notification.email` | *(email)* | Notification email for Form AJ |
| reports | `reports.form.ij.notification.email` | *(email)* | Notification email for Form IJ |

> The rule reads these at runtime as `reports.form.{letter}.notification.email` (lowercased). If a property is missing, the email field on the report will display a warning message where the email should be.

---

## Lookup List Configuration

The report structure is entirely driven by lookup list attributes. All four lists below must have SCR attributes configured before the report will run.

### `EVENT_RESULT`

Used for Form A to identify the event results that are reportable for each event type. This is used to identify reportable arraignments on CPC Criminal cases. Each event result must have an attribute:

| Attribute Type | Name | Value |
|---|---|---|
| `SCR` | `A` | The event type code to map this result to |


### `SUB_CASE_TYPE`

Maps case/subcase types to report columns. Each reportable item must have an attribute:

| Attribute Type | Name | Value |
|---|---|---|
| `SCR` | *(report letter)* | Column letter (e.g., `A`, `B`, `C`, `V`) |

For Forms AJ and IJ, items with attribute name `J` are picked up for both reports.

### `CASE_DISPOSITION`

Maps disposition types to report rows. Each reportable disposition type must have an attribute:

| Attribute Type | Name | Value |
|---|---|---|
| `SCR` | *(report letter)* | Row number (e.g., `5`, `6`, `7`) — or `TIME` to flag the disposition as inactive for case age calculations |

For Forms AJ and IJ, items with attribute name `J` are picked up for both reports.

### `SCR_FORM_{letter}`

One lookup list per form letter (e.g., `SCR_FORM_A`, `SCR_FORM_B`, `SCR_FORM_AJ`, `SCR_FORM_IJ`). Defines the rows and columns of that form.

- **Columns** — items whose code is a **letter** (A, B, C…). The column's time guideline (in months) goes in the item's **Description** field (e.g., `"95% in 12"`).
- **Rows** — items whose code is a **number** (1, 2, 3…). Row descriptions drive special behavior:
  - Contains `"term total"` → termination total row
  - Contains `"pend end"` → pending end-of-period row (calculated as pending − terminations)
  - Contains `"pend overdue"` → count of overdue pending cases
  - Contains `"highest overdue"` → months the oldest overdue case is past its guideline
  - Contains `"zero"` → hardcoded zero (per court requirement, Form A only)

---

## Data Cleanup (One-Time, Converted Data)

These scripts must be run once before the SCR will produce accurate results on converted case data. Run them in the order listed. Each script outputs a `fixed` list and a `flagged` list to the log — review flagged items for manual correction.

| Order | Rule | Purpose |
|---|---|---|
| 1 | `CLEANUP_CASEDISPOSITION_TYPE` | Removes `/` characters from `dispositionType` values that don't match the current `CASE_DISPOSITION` lookup list (e.g., converts legacy `B/12` to `B12`). Items with no match are flagged for manual review. |
| 2 | `CLEANUP_CASEDISPOSITION_DATE` | Populates blank `cf_beginDate` on CaseDispositions. Logic varies by jurisdiction: General (Criminal) cases use the earliest qualifying arraignment date; Domestic cases use `reopenReasonDate` or the prior disposition's begin date; all others use the case filing date. CPC Criminal cases with no arraignment are counted but not updated — manual review required. |
| 3 | `CLEANUP_SCR_DATA` | Two-pass cleanup. Pass 1: populates blank `cf_reopenDate` on CaseDispositions using arraignment dates (CR/GEN), `reopenReasonDate` (UNDSP), or RO/O status dates. Pass 2: populates blank `dateRemoved` on JUD/ADMINJ CaseAssignments by looking for the next subsequent assignment on the case. Items with no match are flagged. |
| 4 | `CLEANUP_CASEDISPOSITION_DATE2` | Second-pass `cf_reopenDate` cleanup for records that still have a null `cf_reopenDate` but a non-null `cf_beginDate`. Uses begin date (if first or only disposition on case) or `reopenReasonDate` as fallback. |

> **Tip:** Enable `_debug = true` when running cleanup scripts on a test environment to see per-record log output. In production, leave `_debug` off to avoid flooding the log.

---

## Auto-Disposition Workflow

Going forward, CaseDispositions are created and updated automatically as judges are assigned and removed from cases. This keeps the SCR data current without manual entry.

### Workflow

**Name:** `SC Reports: Update Case Disposition on Judge Assign/Remove`  
**Trigger:** Judge assigned to or removed from a case (CaseAssignment add/update)

The workflow fires one of two business rules depending on the situation:

| Scenario | Rule Fired |
|---|---|
| Judge assigned to a case | `ADD_CASEDISP_JUDGE` |
| Judge removed from a case | `UPDATE_CASEDISP_JUDGE` |

### Deploy the Business Rules

Deploy both rules as **Standard** business rules with a single input parameter `_ca` of type `CaseAssignment`.

#### `ADD_CASEDISP_JUDGE`

Creates or updates the UNDSP CaseDisposition when a judge is assigned. Behavior:

- Skips Probate cases (`caseJurisdiction == PROB`).
- Ensures `dateAssigned` is set on the assignment (defaults to `statusDate` or today).
- Finds the most recent non-UNDSP disposition on the case to carry forward reporting fields.
- Creates a new UNDSP disposition (or updates the existing one) with:
  - `dispositionType` = `UNDSP`
  - `reopenReasonDate` / `reopenReason` = assignment date / `OPEN`
  - `cf_beginDate` and `cf_reopenDate` carried forward from the prior disposition if one exists
  - `dispositionSource` (action code) carried forward from the prior disposition
- For non-Criminal General cases with no prior disposition, sets `cf_beginDate` to the assignment date (i.e., this is the case's first reportable period).
- CPC Criminal cases (`GEN/CR`) do not get `cf_beginDate` set here — that comes from the arraignment event.

#### `UPDATE_CASEDISP_JUDGE`

Closes the current UNDSP disposition with a transfer type when a judge is removed. Behavior:

- Skips Probate cases.
- Finds the current UNDSP disposition and sets `dispositionType` based on jurisdiction:

| Assignment Role | Jurisdiction | Disposition Type Set |
|---|---|---|
| `ADMINJ` | Any | `MCATIJ` |
| `JUD` | `GEN` | `CPCTRANSFR` |
| `JUD` | `DOM` | `B12` |
| `JUD` | `MUNI` | `MCITR` |
| `JUD` | Other | *(no action)* |

- Sets `dispositionDate` to today and ensures `dateRemoved` is set on the assignment.

### Configure the Workflow

Set up the workflow to trigger on CaseAssignment save (add and update). The workflow should:

1. Check whether the assignment is being added (judge assigned) or the judge is being removed (`dateRemoved` is newly set).
2. Fire `ADD_CASEDISP_JUDGE` passing the CaseAssignment as `_ca` when a judge is assigned.
3. Fire `UPDATE_CASEDISP_JUDGE` passing the CaseAssignment as `_ca` when a judge is removed.

---

## Error Handling

Configuration errors cause the rule to throw a `ReportException`, which stops the report and displays the error message directly in eCourt. If a report fails to generate, read the error message shown on screen — it will identify the specific misconfiguration.

| Error Message | Cause |
|---|---|
| `Couldn't find a DirPerson matching {code}` | Judge selector value doesn't resolve to a valid DirPerson. Check the judge dropdown query. (This should never happen.) |
| `No SCR {letter} attributes are defined for Lookup List EVENT_RESULT` | Form A only — `EVENT_RESULT` has no items with `SCR/A` attributes. |
| `No SCR {letter} attributes are defined for Lookup List SUB_CASE_TYPE` | `SUB_CASE_TYPE` has no items with `SCR/{letter}` attributes for this form. |
| `No SCR {letter} attributes are defined for Lookup List CASE_DISPOSITION` | `CASE_DISPOSITION` has no items with `SCR/{letter}` attributes for this form. |
| `Couldn't find any column/row definitions in Lookup List SCR_FORM_{letter}` | The form's lookup list is missing or has no letter/number-coded items. |
| `Column code {x} was not found in SCR_FORM_{letter}` | A `SUB_CASE_TYPE` SCR attribute value doesn't match any column code in the form list. Check that the attribute Value field is set. |
| `Definition for row {n} was not found in SCR_FORM_{letter}` | A `CASE_DISPOSITION` SCR attribute value doesn't match any row code in the form list. |
| `Column {x} doesn't have a guideline defined` | The column item in `SCR_FORM_{letter}` has no number at the end of its Description. |

---

## Testing

1. **Verify lookup lists** — confirm `SCR_FORM_{letter}`, `SUB_CASE_TYPE`, `CASE_DISPOSITION`, and (for Form A) `EVENT_RESULT` all have the expected SCR attributes.
2. **Run cleanup scripts** on a test environment first or with rollback on and review the `flagged` output before running on production data or with rollback off.
3. **Run the report for a prior period** with `_caseDetails = "Show Case Details + Removed Cases"` to see which cases are included and why any are excluded. The removed-cases section shows the removal reason per case.
4. **Spot-check counts** — use the `_columns` and `_rows` parameters to isolate a single row, column, or cell, then manually count matching CaseDispositions in eCourt to verify.
5. **Test each form letter** (A, B, AJ, IJ) for each judge before go-live. Form B uses `dispositionSource` (action code) to get the report column rather than `subCaseType` — confirm the `SUB_CASE_TYPE` mapping is correct and contains all dispositionSource values (action codes).

---

## Form C — Common Pleas, Probate Division

Form C has a fundamentally different architecture from the other forms. It runs a direct SQL query against Probate case data and has no dependency on lookup list attributes, CaseDispositions, or the auto-disposition workflow. Row placement is hardcoded in the SQL.

### Key Differences from Forms A/B/AJ/IJ

| | Forms A/B/AJ/IJ | Form C |
|---|---|---|
| Reporting period | Monthly | Quarterly |
| Judge selection | Per-judge selector | All Probate cases (no judge input) |
| Data source | eCourt domain objects (`CaseDisposition`) | Direct SQL (`tCase` and related tables) |
| Row/column mapping | Lookup list attributes | Hardcoded in SQL `CASE` statements |
| Cleanup scripts required | Yes | No |
| Lookup list config required | Yes | No |

### Deploy the Business Rule

Deploy `SUPREME_COURT_REPORT_C` (from `Form_C_Groovy.groovy`) as a **Datasource** business rule.

| Field | Value |
|---|---|
| Report Code | `SUPREME_COURT_REPORT_C` |
| Report Name | Supreme Court Report — Form C |
| Report Style | Headless |
| Root Entity | *(SQL-based — no root entity)* |
| Formats | PDF, XLS |

### Input Parameters

| Parameter | Data Type | UI Type | Method | List Source | Default | Hidden | Required | Notes |
|---|---|---|---|---|---|---|---|---|
| `reportYear` | Integer | Dropdown | Query | ¹ | — | No | Yes | |
| `reportQuarter` | Integer | Dropdown | User Input List | `1,2,3,4` | — | No | Yes | |
| `caseDetails` | Boolean | Boolean Dropdown | Boolean List | ² | `Hide Case Details` | No | Yes | |
| `lastInventoryDate` | Date | Date Picker | — | — | — | No | No | Passes value directly into report header |
| `judgeName` | String | Text Box | — | — | `David D. Brannon` | Yes | No | Update if judge changes |
| `judgeBarNumber` | String | Text Box | — | — | `0079755` | Yes | No | Update if judge changes |
| `notificationEmail` | String | Dropdown | Query | ³ | `weikertm@mcohio.org` | Yes | No | |
| `jurisdiction` | String | Text Box | — | — | `Montgomery` | Yes | No | |

The following parameters are calculated within the JRXML and do not require configuration: `Judge_Code`, `reportBegin`, `reportEnd`, `runDate`.

> There is no judge selector — Form C reports all Probate cases across the division. The cutoff date (`01/01/2010`) is hardcoded in the rule.

**¹ `reportYear` query:**
```sql
with yearlist as 
(select DATEPART(year, GETDATE())-99 as yr 
union all 
select yl.yr + 1 as yr 
from yearlist yl 
where yl.yr + 1 <= DATEPART(year, GETDATE()))
select yr from yearlist order by yr desc;
```

**² `caseDetails` boolean list:**
- `True` = `Show Case Details`
- `False` = `Hide Case Details`

**³ `notificationEmail` query:**
```sql
select cast(value as varchar(255)) as notif
from tSystemProperty
where keyColumn = 'reports.form.c.notification.email'
```

### Deploy the JRXML

Upload `Form_C_Groovy.jrxml` and associate it with the `SUPREME_COURT_REPORT_C` report code.

### Row Placement Logic

Rows are assigned entirely within the SQL query. The main `SELECT` places pending and newly-filed cases; two `UNION ALL` blocks add termination rows for cases closed during the period (once from `Run_Date_Case_Status`, once from `Ending_Case_Status`). Cases that don't match any condition are assigned line `990`/`991`/`992` and excluded from the printed report.

| Case Category / Subcase Type | Pending (filed before period) | Filed in Period | Closed in Period |
|---|---|---|---|
| `EST` + `DEC` party | 1 | 2 | 3 |
| `GRD2` | 7 | 8 | 9 |
| `GRD1` | 11 | 12 | 13 |
| `GRD4/5/6` | — | 15 | — |
| `GRD3` | 16 | 17 | 18 |
| `TRS1` | 20 | 21 | 22 |
| `MSC` | 26 | 27 | 28 |
| `ADP` | 30 | 31 | 32 |
| `MI` | 34 | 35 | 36 |
| `EST12` | 42 | 43 | 44 |
| `BREC` | — | 46 | 47 |
| Doc `PMSC_1.8` filed in period | — | 48 | 49 |
| Doc `PMAR_LICENSE` filed in period | — | 50 | — |

> Note: `BREC` subcases with `cf_subCaseSubType = CORBR` are excluded (mapped to `NULL` subcase type before row assignment).

### Safety Limit

The rule aborts and returns an empty dataset if the initial query returns more than **20,000 cases**. If the report produces no output, check the eCourt logs for the message `More than 20000 cases found, aborting`.

### Testing

1. Run the report for a prior quarter and verify total case counts against a manual count of open/filed/closed Probate cases for that period.
2. Spot-check a few cases near row boundaries (e.g., a case filed exactly on the first day of the quarter) to confirm correct row assignment.
3. Verify `BREC` cases with and without `cf_subCaseSubType = CORBR` are handled correctly.