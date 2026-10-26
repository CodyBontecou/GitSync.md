#!/usr/bin/env python3
"""Save Apple purchase/discovery reports and summarize a pricing-test window.

Requires authenticated `asc` and `curl`. Uses event dates, replacing overlapping
daily report snapshots with the latest processed snapshot for each event date.
Signed download URLs are used in memory only. No vendor number is required.
"""

import argparse
import concurrent.futures
import csv
import datetime as dt
import gzip
import io
import json
import subprocess
from decimal import Decimal
from pathlib import Path

REQUEST = "e7228014-7aac-40cc-937d-7e9ad32121e4"
REPORTS = {"purchases": "r12", "discovery": "r14"}


def api(path, *query):
    command = ["asc", "api", "GET", path, "--paginate"]
    for value in query:
        command.extend(["--query", value])
    return json.loads(subprocess.check_output(command))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", required=True, type=dt.date.fromisoformat)
    parser.add_argument("--end", required=True, type=dt.date.fromisoformat)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--request-id", default=REQUEST)
    parser.add_argument("--content-id", action="append", default=[],
                        help="Filter purchases to these Apple content/IAP IDs; repeat for multiple products")
    args = parser.parse_args()
    if args.end < args.start:
        parser.error("end must be on or after start")
    args.output.mkdir(parents=True, exist_ok=True)
    snapshots = {}
    coverage = {}
    for name, prefix in REPORTS.items():
        instances = api(f"/v1/analyticsReports/{prefix}-{args.request_id}/instances",
                        "filter[granularity]=DAILY", "limit=200")["data"]
        # Include later processing dates: Apple's reports lag the event dates.
        selected = sorted((r for r in instances if
                           r["attributes"]["processingDate"] >= str(args.start)),
                          key=lambda r: r["attributes"]["processingDate"])

        def download(instance):
            date = instance["attributes"]["processingDate"]
            target = args.output / f"{name}-{date}.tsv"
            if not target.exists():
                segments = api(f"/v1/analyticsReportInstances/{instance['id']}/segments")["data"]
                if not segments:
                    raise RuntimeError(f"No segments available for {name} {date}")
                rows = []
                fields = None
                for segment in segments:
                    data = subprocess.check_output([
                        "curl", "-fsSL", "--retry", "2", "--max-time", "60",
                        segment["attributes"]["url"],
                    ], stderr=subprocess.PIPE)
                    if data[:2] == b"\x1f\x8b":
                        data = gzip.decompress(data)
                    reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig")), delimiter="\t")
                    fields = reader.fieldnames
                    rows.extend(reader)
                with target.open("w") as handle:
                    writer = csv.DictWriter(handle, fieldnames=fields, delimiter="\t")
                    writer.writeheader()
                    writer.writerows(rows)
            return list(csv.DictReader(target.open(), delimiter="\t"))

        by_date = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            for rows in pool.map(download, selected):
                grouped = {}
                for row in rows:
                    date = row["Date"]
                    if str(args.start) <= date <= str(args.end):
                        grouped.setdefault(date, []).append(row)
                by_date.update(grouped)
        snapshots[name] = [r for rows in by_date.values() for r in rows]
        coverage[name] = sorted(by_date)

    def summarize(territory=None, content=None):
        purchases = [r for r in snapshots["purchases"] if
                     (r["Content Apple Identifier"] in args.content_id if args.content_id else
                      r["Purchase Type"] == "App purchase") and
                     (content is None or r["Content Apple Identifier"] == content) and
                     (territory is None or r["Territory"] == territory)]
        discovery = [r for r in snapshots["discovery"] if
                     territory is None or r["Territory"] == territory]
        views = sum(int(r["Counts"]) for r in discovery if
                    r["Event"] == "Page view" and r["Page Type"] == "Product page")
        page_purchases = [r for r in purchases if r["Page Type"] == "Product page"]
        count = sum(int(r["Purchases"]) for r in purchases)
        page_count = sum(int(r["Purchases"]) for r in page_purchases)
        proceeds = sum((Decimal(r["Proceeds in USD"]) for r in purchases), Decimal(0))
        common_dates = set(coverage["purchases"]) & set(coverage["discovery"])
        matched_purchases = [r for r in page_purchases if r["Date"] in common_dates]
        matched_count = sum(int(r["Purchases"]) for r in matched_purchases)
        matched_views = sum(int(r["Counts"]) for r in discovery if
                            r["Date"] in common_dates and r["Event"] == "Page view"
                            and r["Page Type"] == "Product page")
        page_proceeds = sum((Decimal(r["Proceeds in USD"]) for r in matched_purchases), Decimal(0))
        return {
            "purchases": count, "proceeds_usd": str(proceeds),
            "sales_usd": str(sum((Decimal(r["Sales in USD"]) for r in purchases), Decimal(0))),
            "product_page_views": views, "page_attributed_purchases": page_count,
            "rate_event_dates": sorted(common_dates),
            "rate_page_views": matched_views, "rate_page_purchases": matched_count,
            "page_purchase_rate": matched_count / matched_views if matched_views else None,
            "page_attributed_proceeds_per_view_usd": float(page_proceeds / matched_views) if matched_views else None,
        }

    summary = {
        "start": str(args.start), "end": str(args.end),
        "request_id": args.request_id, "content_ids": args.content_id,
        "generated_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "event_dates_present": coverage,
        "all_territories": summarize(), "US": summarize("US"),
        "by_territory": {code: summarize(code) for code in sorted({
            r["Territory"] for rows in snapshots.values() for r in rows
        })},
        "by_content": {content: {
            "all_territories": summarize(content=content),
            "by_territory": {code: summarize(code, content) for code in sorted({
                r["Territory"] for rows in snapshots.values() for r in rows
            })},
        } for content in args.content_id},
        "purchase_types_seen": sorted({r["Purchase Type"] for r in snapshots["purchases"]}),
        "limitations": [
            "Apple reporting delays and privacy thresholds can leave missing dates or rows; missing data is not zero.",
            "Page purchase rate uses attributed app purchases divided by page-view events, not unique visitors or Apple's dashboard conversion rate.",
            "Page rates use only event dates present in both reports; totals use every available event date in each report.",
            "Refund counts are not supplied by this summary and must be checked separately when accessible.",
            "Daily reports overlap; only the latest processed snapshot for each event date is counted.",
        ],
    }
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
