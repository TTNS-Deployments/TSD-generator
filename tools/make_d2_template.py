"""
Builds a D2 TSD template from a filled-in sample .docx.

In the samples, every value that changes per customer is highlighted yellow. This script:
  1. Replaces each highlighted text with a {{placeholder}} (mapping below) and removes the highlight.
     Highlighted text that is not in the mapping but is listed in KEEP is kept as fixed text, unhighlighted.
  2. Replaces the paragraph holding the BOQ picture with a {{boq_table}} marker paragraph.
  3. Fails loudly on any highlighted text it doesn't recognise, so nothing is silently left behind.

Only plain string edits on word/document.xml are made; everything else in the package is copied unchanged.

Usage:  python tools/make_d2_template.py <sample.docx> <output.docx> <type>
        type: azure-subscription | self-deployment-ri | csp-to-csp
"""
import re
import sys
import zipfile

# ---------------------------------------------------------------------------------------------
# Per-type mapping: highlighted text (as it appears, runs joined) -> replacement text.
# A list value means "use these in order for successive occurrences".
MAPPINGS = {
    "azure-subscription": {
        "map": {
            "ABC Technologies Limited": "{{customer_name}}",
            "ABC Technologies Limited,": "{{customer_name}},",
            "XYZ Pvt. Ltd.": "{{end_customer}}",
            "Sept 09, 2025": "{{date}}",
            "QT57862": "{{quote_id}}",
            "PAM Name": "{{am_name}}",
            "PAM Email id": "{{am_email}}",
            "SA Name": "{{sa_name}}",
            "SA Email id": "{{sa_email}}",
            "Client SPOC name": "{{spoc_name}}",
            "Cloud Delivery &amp; Support": "{{spoc_role}}",
            "Client email id": "{{spoc_email}}",
            " WEST": "{{region}}",
            "₹ 12,000": "{{monthly_usage}}",
            "-16 %": "-{{discount_pct}} %",
            "₹ 1,920": "{{discount_amount}}",
            "₹ 10,080": ["{{post_discount_usage}}", "{{total_monthly_billing}}"],
        },
        # Highlighted in the sample but fixed text in every D2 document
        "keep": {"Self-Deployment", "4.2","Azure Monthly Consumption Estimates.", "Azure Monthly Consumption Estimate", "s."},
        # The BOQ picture to replace with the {{boq_table}} marker
        "boq_image_rel": "rId16",
    },
    "self-deployment-ri": {
        "map": {
            "ABC Technology Limited": "{{customer_name}}",
            "ABC Technology Limited,": "{{customer_name}},",
            "XYZ Pvt. Ltd.": "{{end_customer}}",
            "Sept 24, 2025": "{{date}}",
            "QT60041": "{{quote_id}}",
            "PAM Name": "{{am_name}}",
            "PAM Email id": "{{am_email}}",
            "SA Name": "{{sa_name}}",
            "SA Email id": "{{sa_email}}",
            "Client SPOC name": "{{spoc_name}}",
            "Cloud Delivery &amp; Support": "{{spoc_role}}",
            "Client email id": "{{spoc_email}}",
            " WEST": "{{region}}",
            # No discount for reserved instances: total billing = expected usage
            "₹ 109,491": ["{{monthly_usage}}", "{{total_monthly_billing}}"],
        },
        "keep": {"Self-Deployment", "4.2", "Azure Monthly Consumption Estimates.",
                 "RI purchase/enablement against the required resources (No action if already done)"},
        "boq_image_rel": "rId16",
    },
}

# Innermost run: <w:r ...> ... </w:r> with no nested <w:r> inside (text-box runs contain nested runs)
RUN_RE = re.compile(r"<w:r(?:\s[^>]*)?>(?:(?!<w:r[\s>]).)*?</w:r>", re.S)
T_RE = re.compile(r"(<w:t(?:\s[^>]*)?>)([^<]*)(</w:t>)")
HIGHLIGHT_RE = re.compile(r'<w:highlight w:val="yellow"\s*/>')
YELLOW_FILL_RE = re.compile(r'<w:shd [^>]*w:fill="FFFF00"[^>]*/>')


TR_RE = re.compile(r"<w:tr[\s>].*?</w:tr>", re.S)
TC_RE = re.compile(r"<w:tc>.*?</w:tc>", re.S)
P_RE = re.compile(r"<w:p[\s>].*?</w:p>", re.S)
BOLD_RE = re.compile(r"<w:b(?:Cs)?(?:\s[^>]*)?/>")
# Elements allowed in a paragraph-mark rPr but not in a run rPr
PARA_ONLY_RPR = re.compile(r"<w:(?:ins|del|moveFrom|moveTo)\b[^>]*/>|<w:rPrChange\b.*?</w:rPrChange>", re.S)


def set_cell_text(tc, text, plain=False):
    """Puts text in a table cell: first <w:t> of the first paragraph; every other text in the cell is cleared.
    An empty cell gets a new run that copies the paragraph-mark formatting."""
    paras = list(P_RE.finditer(tc))
    if not paras:
        return tc
    out, last = [], 0
    for i, pm in enumerate(paras):
        p = pm.group(0)
        if i == 0:
            if T_RE.search(p):
                done = [False]

                def put(tm):
                    if not done[0]:
                        done[0] = True
                        return '<w:t xml:space="preserve">' + text + tm.group(3)
                    return tm.group(1) + tm.group(3)

                p = T_RE.sub(put, p)
            else:
                m = re.search(r"<w:pPr>.*?<w:rPr>(.*?)</w:rPr>.*?</w:pPr>", p, re.S)
                # Keep font/size/colour of the cell, but not bold: answers should look the same in every row
                rpr = "<w:rPr>" + BOLD_RE.sub("", PARA_ONLY_RPR.sub("", m.group(1))) + "</w:rPr>" if m else ""
                p = p[: p.rfind("</w:p>")] + "<w:r>" + rpr + '<w:t xml:space="preserve">' + text + "</w:t></w:r></w:p>"
        else:
            p = T_RE.sub(lambda tm: tm.group(1) + tm.group(3), p)
        if plain:
            p = BOLD_RE.sub("", p)
        out.append(tc[last:pm.start()] + p)
        last = pm.end()
    out.append(tc[last:])
    return "".join(out)


def fill_table_cells(xml, anchor, cells, plain=False):
    """cells: {(row, col): text} for the table whose text contains anchor. plain=True removes bold in those cells."""
    pos = xml.find(anchor)
    if pos == -1:
        sys.exit(f"Table anchor not found: {anchor}")
    t_start = xml.rfind("<w:tbl>", 0, pos)
    t_end = xml.find("</w:tbl>", pos) + len("</w:tbl>")
    tbl = xml[t_start:t_end]
    rows = list(TR_RE.finditer(tbl))
    new_tbl, last = [], 0
    for r, rm in enumerate(rows):
        row = rm.group(0)
        tcs = list(TC_RE.finditer(row))
        new_row, rl = [], 0
        for c, cm in enumerate(tcs):
            tc = cm.group(0)
            if (r, c) in cells:
                tc = set_cell_text(tc, cells[(r, c)], plain)
            new_row.append(row[rl:cm.start()] + tc)
            rl = cm.end()
        new_row.append(row[rl:])
        new_tbl.append(tbl[last:rm.start()] + "".join(new_row))
        last = rm.end()
    new_tbl.append(tbl[last:])
    missing = [rc for rc in cells if rc[0] >= len(rows) or rc[1] >= len(TC_RE.findall(rows[rc[0]].group(0)))]
    if missing:
        sys.exit(f"Cells not found in table '{anchor}': {missing}")
    return xml[:t_start] + "".join(new_tbl) + xml[t_end:]


def _calc_cells():
    """Calculations table (CSP to CSP): row 0 = currency + rate, row 1 = months, rows 2-4 = RI / Azure / Total."""
    cells = {(0, 5): "{{fx_rate}}"}
    for i in range(1, 4):
        cells[(1, i)] = "{{m%d}}" % i
    cols = ["m1", "m2", "m3", "avg", "inr", "disc_pct", "disc", "total"]
    for r, prefix in ((2, "ri"), (3, "az"), (4, "tot")):
        for c, name in enumerate(cols, start=1):
            if prefix == "tot" and name in ("disc_pct", "disc"):
                cells[(r, c)] = ""
            else:
                cells[(r, c)] = "{{%s_%s}}" % (prefix, name)
    return cells


MAPPINGS["csp-to-csp"] = {
    "map": {
        "ABC Technologies Limited": "{{customer_name}}",
        "ABC Technologies Limited,": "{{customer_name}},",
        "XYZ Pvt. Ltd.": "{{end_customer}}",
        "Sept 09, 2025": "{{date}}",
        "QT57862": "{{quote_id}}",
        "PAM Name": "{{am_name}}",
        "PAM Email id": "{{am_email}}",
        "SA Name": "{{sa_name}}",
        "SA Email id": "{{sa_email}}",
        "Client SPOC name": "{{spoc_name}}",
        "Cloud Delivery &amp; Support": "{{spoc_role}}",
        "Client Email id": "{{spoc_email}}",
        " WEST": "{{region}}",
        "$$": "{{currency_symbol}}",
        # Commercials: A = PayG (Azure) INR, B = discount on A, C = RI INR, total = A - B + C
        "INR 43,287": "INR {{payg_inr}}",
        "16% on MRC": "{{discount_pct_text}} on MRC",
        "INR 6,926": "INR {{discount_inr}}",
        "INR 4,387": "INR {{ri_inr}}",
        "INR 40,748/-": "INR {{total_inr}}/-",
    },
    "keep": {"3.", "Prerequisites"},
    "cells": {
        # Prerequisites: column 2 (Answers) of rows 1-11
        "Information Needed": {(r, 2): "{{pre_%d}}" % r for r in range(1, 12)},
        "Last 3 Months usage": _calc_cells(),
    },
    # Answers should look the same in every row (the sample's empty cells carry stray bold)
    "plain_cells": {"Information Needed"},
}


def build(sample, output, kind):
    cfg = MAPPINGS[kind]
    with zipfile.ZipFile(sample) as zin:
        xml = zin.read("word/document.xml").decode("utf-8")

        # ---- 1. group consecutive highlighted runs (same paragraph, no text in between)
        runs = [m for m in RUN_RE.finditer(xml) if HIGHLIGHT_RE.search(m.group(0))]
        groups = []
        for m in runs:
            if groups:
                gap = xml[groups[-1][-1].end():m.start()]
                if "<w:t" not in gap and "</w:p>" not in gap:
                    groups[-1].append(m)
                    continue
            groups.append([m])

        counters = {}
        edits = []  # (start, end, new_text)
        unknown = []
        for g in groups:
            text = "".join(t[1] for m in g for t in T_RE.findall(m.group(0)))
            if text in cfg["map"]:
                rep = cfg["map"][text]
                if isinstance(rep, list):
                    i = counters.get(text, 0)
                    rep = rep[min(i, len(rep) - 1)]
                    counters[text] = i + 1
            elif text in cfg["keep"] or not text.strip():
                rep = None
            else:
                unknown.append(text)
                continue
            for idx, m in enumerate(g):
                run = HIGHLIGHT_RE.sub("", m.group(0))
                if rep is not None:
                    first = [True]

                    def put(tm):
                        if idx == 0 and first[0]:
                            first[0] = False
                            return '<w:t xml:space="preserve">' + rep + tm.group(3)
                        return tm.group(1) + tm.group(3)

                    run = T_RE.sub(put, run)
                edits.append((m.start(), m.end(), run))

        if unknown:
            sys.exit("Unrecognised highlighted text (add it to MAPPINGS): " + repr(sorted(set(unknown))))

        for start, end, new in sorted(edits, reverse=True):
            xml = xml[:start] + new + xml[end:]

        # ---- 2. BOQ picture -> {{boq_table}} marker paragraph (only documents that have a BOQ)
        if cfg.get("boq_image_rel"):
            pos = xml.find('r:embed="' + cfg["boq_image_rel"] + '"')
            if pos == -1:
                sys.exit("BOQ picture not found")
            p_start = [m.start() for m in re.finditer(r"<w:p[\s>]", xml[:pos])][-1]
            p_end = xml.find("</w:p>", pos) + len("</w:p>")
            xml = xml[:p_start] + '<w:p><w:r><w:t>{{boq_table}}</w:t></w:r></w:p>' + xml[p_end:]

        # ---- 3. Placeholders by table cell, for values that are not highlighted (e.g. empty answer cells)
        for anchor, cells in cfg.get("cells", {}).items():
            xml = fill_table_cells(xml, anchor, cells, anchor in cfg.get("plain_cells", ()))

        # Remaining highlights sit on paragraph marks (pPr/rPr), not on text: remove them too
        xml = HIGHLIGHT_RE.sub("", xml)
        # Some samples mark input cells with a yellow cell fill instead of a highlight: remove it as well
        xml = YELLOW_FILL_RE.sub("", xml)

        # ---- write the package, replacing only document.xml
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = xml.encode("utf-8") if item.filename == "word/document.xml" else zin.read(item.filename)
                zout.writestr(item, data)

    placeholders = sorted(set(re.findall(r"\{\{[a-z0-9_]+\}\}", xml)))
    print(f"Wrote {output}")
    print("Placeholders:", ", ".join(placeholders))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    build(sys.argv[1], sys.argv[2], sys.argv[3])
