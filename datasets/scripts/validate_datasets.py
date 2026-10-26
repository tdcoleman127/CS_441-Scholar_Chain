"""Validate datasets/dataset-manifest.yaml and the committed samples. Owner: dataset-engineer.

Usage: python -E validate_datasets.py [--root REPO_ROOT]

Prints one line per check ("CHECK PASS ...", "CHECK FAIL ...", "WARN ...") and a final
"RESULT PASS ..." or "RESULT FAIL ..." line.

Besides per-dataset fields, samples, and checksums, it checks the human_decisions records
(recorded_as wording, applies_to, decision_refs), open_questions status and resolution
(a human decision, or owner-fix with dated command evidence), affiliation_handling for the
institution-level-only decision, confirmation_download blocks (including the human-approved
max_bytes bound that every full-tier sources.tsv artifact needs, and a chosen_file that matches
its sources.tsv row),
the Crossref REST-only access path, and linkage-key namespaces against the core IdNamespace
wire names (warning when modules/core does not yet define a namespace the manifest uses). Exit code 0 on pass, 1 on any failure, 2 on a usage
or environment error. Needs Python 3.8+ and PyYAML; nothing else, and no network access.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import re
import sys
import xml.parsers.expat

try:
    import yaml  # PyYAML
except ImportError:  # pragma: no cover - environment check
    print("RESULT FAIL reason=PyYAML-not-installed (pip install pyyaml)")
    sys.exit(2)

SUPPORTED_MANIFEST_VERSIONS = {1}
# Wire names of SourceType in modules/core (domain-engineer). Kept in sync by review.
SOURCE_TYPES = {
    "dblp", "openalex", "opencitations", "crossref", "arxiv",
    "orcid", "semantic-scholar", "papers-with-code",
}
REQUIRED_FIELDS = [
    "source", "license", "snapshot", "format", "size_estimate", "s3_prefixes", "checksums",
    "subset_rule", "schema", "linkage_keys", "update_method", "limitations", "privacy_notes",
    "validation_commands",
]
SAMPLE_MAX_BYTES = 65536
MIN_ENRICHMENT = 3
S3_PREFIX_RE = re.compile(r"^s3://\$\{[a-z][a-z0-9_]*\}/[A-Za-z0-9_./=${}-]*/$")
HEX_RE = {"md5": re.compile(r"^[0-9a-f]{32}$"), "sha256": re.compile(r"^[0-9a-f]{64}$")}
PENDING = "to be recorded at download"
ISO_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# Wire names of IdNamespace in modules/core (domain-engineer). omid and pmid were added by
# domain-engineer in phase 03; the validator warns if core ever lacks a namespace the manifest uses.
CORE_NAMESPACES = {"doi", "dblp", "openalex", "arxiv", "s2", "pwc", "issn", "ror", "orcid", "sc1", "omid", "pmid"}
CORE_ENUMS = os.path.join("modules", "core", "src", "main", "scala", "edu", "uic", "cs441", "scholarchain",
                          "core", "model", "Enums.scala")
# Linkage keys whose name is itself a core namespace must map to that namespace.
KEY_NAMED_NAMESPACES = {"doi", "orcid", "omid", "pmid", "ror"}
DECISION_ID_RE = re.compile(r"^HD-\d{2}-\d{2}$")
QUESTION_STATUSES = {"open", "closed", "deferred"}
RUNTIME_SINKS = {"neptune", "dynamodb", "s3-vectors", "runtime-export"}
CONFIRMATION_STATUSES = {"approved-in-principle-not-run", "run"}
# A question closed by another owner's repair (not by a human decision) names this resolution,
# the agent that fixed it, and the observed evidence (command, observation, date).
OWNER_FIX = "owner-fix"
SOURCES_TSV = os.path.join("datasets", "scripts", "sources.tsv")


class Report:
    def __init__(self) -> None:
        self.passed = 0
        self.failed: list[str] = []
        self.warnings: list[str] = []

    def check(self, ok: bool, what: str) -> bool:
        if ok:
            self.passed += 1
            print(f"CHECK PASS {what}")
        else:
            self.failed.append(what)
            print(f"CHECK FAIL {what}")
        return ok

    def warn(self, what: str) -> None:
        self.warnings.append(what)
        print(f"WARN {what}")


def is_https(u: object) -> bool:
    return isinstance(u, str) and u.startswith("https://") and " " not in u


def nonempty(v: object) -> bool:
    if v is None:
        return False
    if isinstance(v, (str, list, dict)):
        return len(v) > 0
    return True


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def walk_unknowns(node: object, path: str, out: list[str]) -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            walk_unknowns(v, f"{path}.{k}", out)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk_unknowns(v, f"{path}[{i}]", out)
    elif isinstance(node, str) and node.strip().lower().startswith(("unknown", "unresolved")):
        out.append(path)


def count_xml(path: str, record_element: str) -> tuple[bool, int, str]:
    """Parse with expat, resolving a SYSTEM DTD that sits next to the file (no traversal)."""
    base_dir = os.path.dirname(os.path.abspath(path))
    depth = 0
    count = 0
    p = xml.parsers.expat.ParserCreate(namespace_separator=" ")
    p.SetParamEntityParsing(xml.parsers.expat.XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE)

    def ext(context, base, system_id, public_id):  # noqa: ARG001
        if not system_id or os.path.basename(system_id) != system_id:
            return 0
        dtd = os.path.join(base_dir, system_id)
        if not os.path.isfile(dtd):
            return 0
        sub = p.ExternalEntityParserCreate(context)
        with open(dtd, "rb") as f:
            sub.Parse(f.read(), True)
        return 1

    def start(name, attrs):  # noqa: ARG001
        nonlocal depth, count
        depth += 1
        local = name.rsplit(" ", 1)[-1]
        if (record_element == "*" and depth == 2) or (record_element != "*" and local == record_element):
            count += 1

    def end(name):  # noqa: ARG001
        nonlocal depth
        depth -= 1

    p.ExternalEntityRefHandler = ext
    p.StartElementHandler = start
    p.EndElementHandler = end
    try:
        with open(path, "rb") as f:
            p.Parse(f.read(), True)
        return True, count, ""
    except xml.parsers.expat.ExpatError as e:
        return False, count, str(e)


def count_jsonl(path: str) -> tuple[int, int]:
    records = malformed = 0
    with open(path, "rb") as f:
        for raw in f:
            if not raw.strip():
                continue
            records += 1
            try:
                if not isinstance(json.loads(raw.decode("utf-8")), dict):
                    malformed += 1
            except (UnicodeDecodeError, json.JSONDecodeError):
                malformed += 1
    return records, malformed


def count_csv(path: str) -> tuple[int, int]:
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.reader(io.StringIO(f.read())))
    if not rows:
        return 0, 0
    width = len(rows[0])
    body = [r for r in rows[1:] if r]
    return len(body), sum(1 for r in body if len(r) != width)


def validate(root: str) -> Report:
    rep = Report()
    mpath = os.path.join(root, "datasets", "dataset-manifest.yaml")
    if not rep.check(os.path.isfile(mpath), "manifest exists datasets/dataset-manifest.yaml"):
        return rep
    try:
        with open(mpath, encoding="utf-8") as f:
            m = yaml.safe_load(f)
    except yaml.YAMLError as e:
        rep.check(False, f"manifest parses as YAML ({e.__class__.__name__})")
        return rep
    rep.check(isinstance(m, dict), "manifest top level is a mapping")
    if not isinstance(m, dict):
        return rep
    rep.check(m.get("manifest_version") in SUPPORTED_MANIFEST_VERSIONS,
              f"manifest_version supported ({m.get('manifest_version')!r})")
    datasets = m.get("datasets")
    if not rep.check(isinstance(datasets, list) and datasets, "datasets is a non-empty list"):
        return rep

    ids = [d.get("id") for d in datasets if isinstance(d, dict)]
    rep.check(len(ids) == len(set(ids)) == len(datasets), "dataset ids are unique mappings")
    selected = [d for d in datasets if isinstance(d, dict) and d.get("status") == "selected"]
    primaries = [d for d in selected if d.get("role") == "primary"]
    rep.check(len(primaries) == 1 and primaries[0].get("id") == "dblp",
              "exactly one selected primary dataset and it is dblp")
    enrich = [d for d in selected if d.get("role") == "enrichment"]
    rep.check(len(enrich) >= MIN_ENRICHMENT,
              f"at least {MIN_ENRICHMENT} selected enrichment datasets ({len(enrich)})")

    declared_samples: dict[str, str] = {}
    sums_by_path: dict[str, str] = {}
    used_namespaces: set[str] = set()

    for d in datasets:
        if not isinstance(d, dict):
            rep.check(False, "dataset entry is a mapping")
            continue
        did = d.get("id", "<missing id>")
        rep.check(d.get("source_type") in SOURCE_TYPES, f"{did}: source_type is a known SourceType wire name")
        rep.check(d.get("status") == "selected", f"{did}: status is selected (excluded go to excluded_candidates)")
        rep.check(d.get("role") in {"primary", "enrichment"}, f"{did}: role is primary or enrichment")
        for field in REQUIRED_FIELDS:
            rep.check(nonempty(d.get(field)), f"{did}: required field '{field}' present and non-empty")

        src = d.get("source") or {}
        rep.check(nonempty(src.get("publisher")) and is_https(src.get("homepage")),
                  f"{did}: source has publisher and https homepage")
        ev = src.get("evidence") or []
        rep.check(bool(ev) and all(is_https(e.get("url")) and ISO_DATE_RE.match(str(e.get("retrieved", "")))
                                   and nonempty(e.get("fact")) for e in ev),
                  f"{did}: source.evidence entries have https url, fact, retrieved date")

        lic = d.get("license") or {}
        rep.check(nonempty(lic.get("name")) and is_https(lic.get("url")) and is_https(lic.get("evidence_url")),
                  f"{did}: license has name, https url, https evidence_url")
        rep.check(nonempty(lic.get("attribution")) and nonempty(lic.get("redistribution")),
                  f"{did}: license records attribution and redistribution terms")

        snap = d.get("snapshot") or {}
        rep.check(nonempty(snap.get("version")) and nonempty(snap.get("date")) and is_https(snap.get("evidence_url")),
                  f"{did}: snapshot has version, date, https evidence_url")

        size = d.get("size_estimate") or {}
        rep.check(nonempty(size.get("text")) and is_https(size.get("evidence_url")),
                  f"{did}: size_estimate has text and https evidence_url")

        s3 = d.get("s3_prefixes") or {}
        rep.check(isinstance(s3, dict) and s3 and all(isinstance(v, str) and S3_PREFIX_RE.match(v) for v in s3.values()),
                  f"{did}: s3_prefixes are placeholder templates s3://${{...}}/.../ (no literal bucket names)")

        schema = d.get("schema")
        rep.check(isinstance(schema, str) and schema.startswith("datasets/schemas/")
                  and os.path.isfile(os.path.join(root, schema)), f"{did}: schema note exists ({schema})")

        lk = d.get("linkage_keys") or []
        rep.check(isinstance(lk, list) and lk and all(nonempty(k.get("key")) and nonempty(k.get("field")) for k in lk),
                  f"{did}: linkage_keys entries have key and field")
        for k in lk if isinstance(lk, list) else []:
            key, ns = k.get("key"), k.get("namespace")
            rep.check(ns in CORE_NAMESPACES or (ns == "none" and nonempty(k.get("namespace_note"))),
                      f"{did}: linkage key {key} namespace {ns!r} is a core IdNamespace wire name, or none with a namespace_note")
            if key in KEY_NAMED_NAMESPACES:
                rep.check(ns == key, f"{did}: linkage key {key} uses namespace {key} (got {ns!r})")
            if ns == "none":
                rep.warn(f"linkage key without core namespace: {did}.{key}")
            if ns in CORE_NAMESPACES:
                used_namespaces.add(ns)

        vc = d.get("validation_commands") or []
        rep.check(isinstance(vc, list) and all(isinstance(c, str) and c.strip() for c in vc),
                  f"{did}: validation_commands is a list of commands")

        cks = d.get("checksums") or {}
        for u in cks.get("upstream") or []:
            algo, val = u.get("algorithm"), str(u.get("value", ""))
            ok = (algo in HEX_RE and (HEX_RE[algo].match(val) or val == PENDING)) or (algo == "none" and val == PENDING)
            rep.check(bool(ok) and nonempty(u.get("file")) and is_https(u.get("evidence_url")),
                      f"{did}: upstream checksum {u.get('file')} is a {algo} digest or '{PENDING}', with evidence_url")
        for s in cks.get("samples") or []:
            sums_by_path[str(s.get("path"))] = str(s.get("sha256"))

        for s in d.get("samples") or []:
            sp = str(s.get("path", ""))
            if sp in declared_samples:
                rep.check(False, f"{did}: sample {sp} declared by only one dataset (also {declared_samples[sp]})")
            declared_samples[sp] = did
            rep.check(sp in sums_by_path, f"{did}: sample {sp} has a checksums.samples entry")
            rep.check(nonempty(s.get("provenance")) and nonempty(s.get("license")),
                      f"{did}: sample {sp} records provenance and license")
            full = os.path.join(root, sp)
            if not rep.check(sp.startswith("datasets/samples/") and os.path.isfile(full), f"{did}: sample {sp} exists"):
                continue
            with open(full, "rb") as f:
                data = f.read()
            ck = next((c for c in cks.get("samples") or [] if c.get("path") == sp), {})
            rep.check(len(data) == ck.get("bytes"), f"{did}: sample {sp} byte count {len(data)} matches manifest {ck.get('bytes')}")
            actual = hashlib.sha256(data).hexdigest()
            if not rep.check(actual == ck.get("sha256"), f"{did}: sample {sp} sha256 matches manifest"):
                if b"\r\n" in data:
                    rep.warn(f"{sp} contains CRLF; the checkout converted line endings (see manifest limitations)")
            rep.check(len(data) <= SAMPLE_MAX_BYTES, f"{did}: sample {sp} is at most {SAMPLE_MAX_BYTES} bytes")

            fmt = s.get("format")
            if fmt == "xml":
                wf, n, err = count_xml(full, str(s.get("record_element", "*")))
                rep.check(wf == bool(s.get("well_formed")),
                          f"{did}: sample {sp} well_formed={wf} as declared{(' (' + err + ')') if err and wf != bool(s.get('well_formed')) else ''}")
                if wf:
                    rep.check(n == s.get("records"), f"{did}: sample {sp} has {n} records (declared {s.get('records')})")
            elif fmt == "jsonl":
                n, bad = count_jsonl(full)
                rep.check(n == s.get("records") and bad == s.get("malformed_records"),
                          f"{did}: sample {sp} has {n} lines, {bad} malformed (declared {s.get('records')}, {s.get('malformed_records')})")
            elif fmt == "csv":
                n, bad = count_csv(full)
                rep.check(n == s.get("records") and bad == s.get("malformed_records"),
                          f"{did}: sample {sp} has {n} rows, {bad} malformed (declared {s.get('records')}, {s.get('malformed_records')})")
            elif fmt == "dtd":
                rep.check(data.startswith(b"<!--") and b"<!ELEMENT dblp" in data, f"{did}: sample {sp} looks like the dblp DTD")
            else:
                rep.check(False, f"{did}: sample {sp} has a supported format (xml|jsonl|csv|dtd), got {fmt!r}")

    for p in sums_by_path:
        rep.check(p in declared_samples, f"checksums.samples path {p} is declared in a samples list")

    # Every committed sample file is declared, and SHA256SUMS agrees with the manifest.
    sdir = os.path.join(root, "datasets", "samples")
    on_disk = []
    for dirpath, _dirs, files in os.walk(sdir):
        for fn in files:
            rel = os.path.relpath(os.path.join(dirpath, fn), root).replace(os.sep, "/")
            if rel != "datasets/samples/SHA256SUMS":
                on_disk.append(rel)
    for rel in sorted(on_disk):
        rep.check(rel in declared_samples, f"file {rel} under datasets/samples is declared in the manifest")
    sums_file = os.path.join(sdir, "SHA256SUMS")
    if rep.check(os.path.isfile(sums_file), "datasets/samples/SHA256SUMS exists"):
        listed = {}
        with open(sums_file, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    digest, _, rel = line.rstrip("\n").partition("  ")
                    listed["datasets/samples/" + rel] = digest
        rep.check(listed == {p: sums_by_path.get(p) for p in listed} and set(listed) == set(sums_by_path),
                  "SHA256SUMS lists exactly the manifest sample paths with the same digests")

    excl = m.get("excluded_candidates") or []
    for e in excl:
        eid = e.get("id", "<missing id>")
        rep.check(e.get("source_type") in SOURCE_TYPES and nonempty(e.get("reason"))
                  and bool(e.get("evidence_urls")) and all(is_https(u) for u in e.get("evidence_urls") or []),
                  f"excluded {eid}: has source_type, reason, https evidence_urls")
    rep.check(not (set(ids) & {e.get("id") for e in excl}), "no dataset is both selected and excluded")

    validate_decisions(m, datasets, excl, rep)
    validate_full_bounds(root, m, datasets, rep)
    check_core_namespaces(root, used_namespaces, rep)

    unknowns: list[str] = []
    walk_unknowns(m, "manifest", unknowns)
    for u in unknowns:
        rep.warn(f"recorded as unknown: {u}")
    return rep


def read_sources(root: str) -> list[list[str]] | None:
    path = os.path.join(root, SOURCES_TSV)
    if not os.path.isfile(path):
        return None
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line and not line.startswith("#"):
                rows.append(line.split("\t"))
    return rows


def validate_full_bounds(root: str, m: dict, datasets: list, rep: Report) -> None:
    """Every full-tier artifact in sources.tsv has a recorded, human-approved byte bound.

    A dataset with full-tier rows must record confirmation_download.max_bytes (an integer that
    covers the summed full-tier estimates, so fetch.sh --full does not refuse it), approved_by (a
    recorded human decision), and invoked_by: human (fetch.sh --full is human-invoked only,
    docs/agents.md section 7b). A chosen_file must match a full-tier row byte for byte.
    """
    rows = read_sources(root)
    if not rep.check(rows is not None, f"{SOURCES_TSV.replace(os.sep, '/')} exists"):
        return
    full: dict[str, list[list[str]]] = {}
    for r in rows:
        if len(r) == 8 and r[2] == "full":
            full.setdefault(r[0], []).append(r)
    dby = {hd.get("id"): hd for hd in m.get("human_decisions") or [] if isinstance(hd, dict)}
    selected = {d.get("id"): d for d in datasets if isinstance(d, dict)}
    for ds in sorted(full):
        rep.check(ds in selected, f"sources.tsv full-tier dataset {ds} is a selected dataset")
    for did, d in selected.items():
        cd = d.get("confirmation_download") or {}
        mb = cd.get("max_bytes")
        frows = full.get(did, [])
        if not frows and mb is None:
            continue
        est_ok = all(sc.isdigit() for sc in (r[6] for r in frows))
        total = sum(int(r[6]) for r in frows) if est_ok else None
        rep.check(isinstance(mb, int) and not isinstance(mb, bool) and mb > 0 and bool(frows) and total is not None
                  and mb >= total,
                  f"{did}: confirmation_download.max_bytes {mb!r} covers the full-tier sources.tsv estimate total {total!r}")
        rep.check(cd.get("approved_by") in dby,
                  f"{did}: confirmation_download.approved_by {cd.get('approved_by')!r} is a recorded human decision")
        rep.check(cd.get("invoked_by") == "human",
                  f"{did}: confirmation_download.invoked_by is human (fetch.sh --full is human-invoked only)")
        cf = cd.get("chosen_file")
        if cf is not None:
            match = [r for r in frows
                     if r[7] == cf.get("name") and r[6] == str(cf.get("listed_bytes")) and r[4] == "md5"
                     and r[5] == cf.get("md5") and r[3] == cf.get("url")]
            rep.check(bool(match) and isinstance(mb, int) and mb >= int(cf.get("listed_bytes") or 0)
                      and is_https(cf.get("evidence_url")),
                      f"{did}: confirmation_download.chosen_file {cf.get('name')!r} matches a full-tier sources.tsv row "
                      f"(name, listed_bytes, md5, url), fits max_bytes, and cites an https evidence_url")


def check_core_namespaces(root: str, used: set[str], rep: Report) -> None:
    """Warn (not fail) when a namespace the manifest uses is not yet in modules/core."""
    path = os.path.join(root, CORE_ENUMS)
    if not os.path.isfile(path):
        rep.warn(f"core IdNamespace source not found ({CORE_ENUMS.replace(os.sep, '/')}); namespace sync not checked")
        return
    with open(path, encoding="utf-8") as f:
        src = f.read()
    present = set(re.findall(r'extends\s+IdNamespace\("([^"]+)"\)', src))
    for ns in sorted(used - present):
        rep.warn(f"linkage namespace {ns} is not yet an IdNamespace in modules/core (domain-engineer)")


def validate_decisions(m: dict, datasets: list, excl: list, rep: Report) -> None:
    """Human decisions, decision references, open-question status, and decision-driven fields."""
    decisions = m.get("human_decisions") or []
    questions = m.get("open_questions") or []
    entries = {d.get("id"): d for d in datasets if isinstance(d, dict)}
    entries.update({e.get("id"): e for e in excl if isinstance(e, dict)})
    if not rep.check(isinstance(decisions, list) and decisions, "human_decisions is a non-empty list"):
        return
    rep.check(isinstance(questions, list) and all(isinstance(q, dict) for q in questions),
              "open_questions is a list of mappings with id and status")
    qby = {q.get("id"): q for q in questions if isinstance(q, dict)}
    rep.check(len(qby) == len(questions), "open question ids are unique")
    dby: dict[str, dict] = {}
    for hd in decisions:
        hid = hd.get("id")
        rep.check(isinstance(hid, str) and bool(DECISION_ID_RE.match(hid)) and hid not in dby,
                  f"human decision {hid}: id is unique and has the form HD-NN-NN")
        dby[hid] = hd
        date = str(hd.get("date", ""))
        rep.check(bool(ISO_DATE_RE.match(date)) and hd.get("recorded_as") == f"human decision, phase {m.get('phase')} human gate, {date}",
                  f"human decision {hid}: recorded_as is 'human decision, phase {m.get('phase')} human gate, {date}'")
        rep.check(nonempty(hd.get("topic")) and nonempty(hd.get("decision")) and nonempty(hd.get("text")),
                  f"human decision {hid}: has topic, decision, and text")
        targets = hd.get("applies_to") or []
        rep.check(bool(targets) and all(t in entries for t in targets),
                  f"human decision {hid}: applies_to names selected or excluded dataset ids")
        for t in targets:
            if t in entries:
                rep.check(hid in (entries[t].get("decision_refs") or []), f"{t}: decision_refs cites {hid}")
        for field, want in (("closes", "closed"), ("defers", "deferred")):
            for qid in hd.get(field) or []:
                q = qby.get(qid) or {}
                rep.check(q.get("status") == want and q.get("resolution") == hid,
                          f"human decision {hid}: {field} {qid}, which has status {want} and resolution {hid}")
    for did, e in entries.items():
        for ref in e.get("decision_refs") or []:
            rep.check(ref in dby, f"{did}: decision_refs entry {ref} is a recorded human decision")
    for qid, q in qby.items():
        st = q.get("status")
        ok = st in QUESTION_STATUSES and nonempty(q.get("question"))
        if st == "closed" and q.get("resolution") == OWNER_FIX:
            ev = q.get("evidence")
            ok = ok and nonempty(q.get("resolved_by")) and isinstance(ev, list) and bool(ev) and all(
                isinstance(e, dict) and nonempty(e.get("command")) and nonempty(e.get("observed"))
                and bool(ISO_DATE_RE.match(str(e.get("observed_on", "")))) for e in ev)
        elif st in {"closed", "deferred"}:
            ok = ok and q.get("resolution") in dby
        elif st == "open":
            ok = ok and nonempty(q.get("owner"))
        rep.check(ok, f"open question {qid}: status {st!r} is open (with owner), closed/deferred (with a decision resolution), "
                      f"or closed as {OWNER_FIX} (with resolved_by and dated command evidence)")

    selected = {d.get("id"): d for d in datasets if isinstance(d, dict)}
    for hid, hd in dby.items():
        kind = hd.get("decision")
        targets = [t for t in hd.get("applies_to") or [] if t in selected]
        if kind in {"excluded", "institution-level-only-at-runtime"}:
            for t in targets:
                notes = selected[t].get("privacy_notes") or []
                rep.check(any(isinstance(n, str) and hid in n and hd.get("recorded_as", "") in n for n in notes),
                          f"{t}: privacy_notes record {hid} as '{hd.get('recorded_as')}'")
        if kind == "institution-level-only-at-runtime":
            for t in targets:
                ah = selected[t].get("affiliation_handling") or {}
                rep.check(ah.get("decision") == hid and nonempty(ah.get("batch_only_fields"))
                          and isinstance(ah.get("runtime_allowed_fields"), list)
                          and RUNTIME_SINKS <= set(ah.get("drop_before") or []),
                          f"{t}: affiliation_handling cites {hid}, lists batch-only fields, and drops them before {sorted(RUNTIME_SINKS)}")
        if kind == "approved-in-principle-not-run":
            for t in targets:
                cd = selected[t].get("confirmation_download") or {}
                st = cd.get("status")
                ok = cd.get("decision") == hid and st in CONFIRMATION_STATUSES and nonempty(cd.get("bound")) and nonempty(cd.get("confirms"))
                if st == "run":
                    ok = ok and nonempty(cd.get("run_evidence"))
                rep.check(ok, f"{t}: confirmation_download cites {hid}, has a bound and fields to confirm, and is not marked run without run_evidence")
        if kind == "rest-api-by-doi-only":
            for t in targets:
                urls = (selected[t].get("source") or {}).get("download_urls") or {}
                extra = [k for k in urls if k != "api" and not str(k).endswith("_excluded")]
                ups = (selected[t].get("checksums") or {}).get("upstream") or []
                bulk = [u.get("file") for u in ups if str(u.get("file", "")).endswith((".tar", ".tar.gz", ".gz"))]
                rep.check(not extra and not bulk and "raw" not in (selected[t].get("s3_prefixes") or {}),
                          f"{t}: only the REST api access path is active under {hid} (extra urls {extra}, bulk checksums {bulk})")


def main(argv: list[str]) -> int:
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    if len(argv) == 2 and argv[0] == "--root":
        root = os.path.abspath(argv[1])
    elif argv:
        print("usage: validate_datasets.py [--root REPO_ROOT]")
        return 2
    rep = validate(root)
    if rep.failed:
        print(f"RESULT FAIL passed={rep.passed} failed={len(rep.failed)} warnings={len(rep.warnings)}")
        return 1
    print(f"RESULT PASS passed={rep.passed} failed=0 warnings={len(rep.warnings)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
