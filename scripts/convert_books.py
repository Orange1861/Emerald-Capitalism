#!/usr/bin/env python3
"""Convert authored book text files into Minecraft book definition JSON.

Book source files live below ``docs/lore/books/<rarity>/``.  Both plain text
and Word ``.docx`` files are supported without third-party Python packages.

The metadata format is one field per paragraph::

    Title: A Short Book
    Author: Village Council
    Rarity: Common

    Page 1: The first page.
    Page 2: The second page.

If ``Page N`` markers are absent, every non-empty paragraph after the metadata
becomes a page in document order.  A single source file may contain multiple
books: every new ``Title`` field ends the current book and starts the next one.
The source folder supplies the rarity when the document does not contain a
``Rarity`` field.  This keeps special books such as Village Manager books easy
to author while still validating an explicit rarity when one is supplied.

Usage:

    python scripts/convert_books.py
    python scripts/convert_books.py --input docs/lore/books --output \
        src/main/resources/data/emeraldcapitalism/library_books
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree


DEFAULT_INPUT = Path("docs/lore/books")
DEFAULT_OUTPUT = Path(
    "src/main/resources/data/emeraldcapitalism/library_books"
)

RARITY_ALIASES = {
    "common": "common",
    "uncommon": "uncommon",
    "rare": "rare",
    "legendary": "legendary",
    "bank rule": "bank_rule",
    "bank rules": "bank_rule",
    "bank_rule": "bank_rule",
    "bank_rules": "bank_rule",
    "village manager": "village_manager",
    "village_manager": "village_manager",
    # Accept the spelling used in the original request as an input alias.
    "village manger": "village_manager",
    "village_manger": "village_manager",
}

ALLOWED_RARITIES = frozenset(RARITY_ALIASES.values())
RANDOM_LIBRARY_RARITIES = frozenset({"common", "uncommon", "rare", "legendary"})

RARITY_POOL_WEIGHTS = {
    "common": 70,
    "uncommon": 23,
    "rare": 6,
    "legendary": 1,
}

# WrittenBookContent's 1.21.1 codec caps titles at 32 characters.
MAX_TITLE_LENGTH = 32
MAX_AUTHOR_LENGTH = 128
MAX_PAGE_COUNT = 100
MAX_PAGE_LENGTH = 8192

FIELD_PATTERN = re.compile(
    r"^\s*(Title|Author|Rarity|Pages)\s*(?::|[-–—])?\s*(.*?)\s*$",
    re.IGNORECASE,
)
PAGE_PATTERN = re.compile(
    r"^\s*Page\s+\[?(\d+)\]?\s*(?::|[-–—])?\s*(.*?)\s*$",
    re.IGNORECASE,
)


class BookParseError(ValueError):
    """Raised when an authored book cannot be converted safely."""


@dataclass(frozen=True)
class BookDefinition:
    title: str
    author: str
    rarity: str
    pages: tuple[str, ...]

    def as_json(self) -> dict[str, object]:
        return {
            "title": self.title,
            "author": self.author,
            "rarity": self.rarity,
            "pages": list(self.pages),
        }


def normalize_rarity(value: str) -> str:
    """Return the canonical resource id for a supported rarity."""

    key = re.sub(r"\s+", " ", value.strip().lower().replace("-", " "))
    try:
        return RARITY_ALIASES[key]
    except KeyError as exc:
        supported = ", ".join(sorted(ALLOWED_RARITIES))
        raise BookParseError(
            f"unsupported rarity {value!r}; expected one of {supported}"
        ) from exc


def slug(value: str) -> str:
    """Create a stable resource filename from a source filename or title."""

    result = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")
    if not result:
        raise BookParseError(f"cannot derive a resource id from {value!r}")
    return result


def _clean_paragraph(value: str) -> str:
    return value.replace("\ufeff", "").replace("\xa0", " ").strip()


def read_txt(path: Path) -> list[str]:
    """Read each line as a paragraph, retaining blank paragraph boundaries."""

    return [_clean_paragraph(line) for line in path.read_text(encoding="utf-8-sig").splitlines()]


WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
WORD = {"w": WORD_NS}


def read_docx(path: Path) -> list[str]:
    """Extract Word paragraphs while preserving explicit line breaks."""

    try:
        with zipfile.ZipFile(path) as archive:
            xml = archive.read("word/document.xml")
    except (KeyError, zipfile.BadZipFile) as exc:
        raise BookParseError(f"invalid .docx file: {path}") from exc

    try:
        root = ElementTree.fromstring(xml)
    except ElementTree.ParseError as exc:
        raise BookParseError(f"invalid Word XML: {path}") from exc

    paragraphs: list[str] = []
    for paragraph in root.findall(".//w:p", WORD):
        pieces: list[str] = []
        for node in paragraph.iter():
            if node.tag == f"{{{WORD_NS}}}t":
                pieces.append(node.text or "")
            elif node.tag in {f"{{{WORD_NS}}}tab", f"{{{WORD_NS}}}br", f"{{{WORD_NS}}}cr"}:
                pieces.append("\n" if node.tag != f"{{{WORD_NS}}}tab" else "\t")
        paragraphs.append(_clean_paragraph("".join(pieces)))
    return paragraphs


def read_source(path: Path) -> list[str]:
    if path.suffix.lower() == ".txt":
        return read_txt(path)
    if path.suffix.lower() == ".docx":
        return read_docx(path)
    raise BookParseError(f"unsupported source type: {path.suffix}")


def _validate_text(value: str, field: str, maximum: int) -> str:
    value = _clean_paragraph(value)
    if not value:
        raise BookParseError(f"{field} is empty")
    if len(value) > maximum:
        raise BookParseError(f"{field} is longer than {maximum} characters")
    return value


def _parse_pages(paragraphs: Iterable[str]) -> tuple[str, ...]:
    """Parse explicit Page N markers or use each paragraph as a page."""

    non_empty = [_clean_paragraph(value) for value in paragraphs if _clean_paragraph(value)]
    explicit_pages: list[list[str]] = []
    current: list[str] | None = None
    saw_marker = False

    for paragraph in non_empty:
        match = PAGE_PATTERN.match(paragraph)
        if match:
            saw_marker = True
            current = []
            explicit_pages.append(current)
            inline_text = _clean_paragraph(match.group(2))
            if inline_text:
                current.append(inline_text)
            continue

        if saw_marker:
            assert current is not None
            current.append(paragraph)

    if saw_marker:
        pages = ["\n".join(page).strip() for page in explicit_pages]
    else:
        pages = non_empty

    pages = [page for page in pages if page]
    if not pages:
        raise BookParseError("no pages were found")
    if len(pages) > MAX_PAGE_COUNT:
        raise BookParseError(f"book has more than {MAX_PAGE_COUNT} pages")
    for number, page in enumerate(pages, start=1):
        if len(page) > MAX_PAGE_LENGTH:
            raise BookParseError(
                f"page {number} is longer than {MAX_PAGE_LENGTH} characters"
            )
    return tuple(pages)


def split_books(paragraphs: Iterable[str]) -> list[list[str]]:
    """Split one source document whenever a new Title field is encountered."""

    books: list[list[str]] = []
    current: list[str] | None = None
    preamble: list[str] = []

    for paragraph in paragraphs:
        paragraph = _clean_paragraph(paragraph)
        field_match = FIELD_PATTERN.match(paragraph) if paragraph else None
        is_title = field_match and field_match.group(1).lower() == "title"

        if is_title:
            if current is not None:
                books.append(current)
            current = [paragraph]
        elif current is not None:
            current.append(paragraph)
        elif paragraph:
            # Ignore document-level headings or whitespace before the first
            # Title. The first Title is the start of the first authored book.
            preamble.append(paragraph)

    if current is not None:
        books.append(current)

    if not books:
        # Keep the normal missing-Title validation/error message for a source
        # that contains no book marker.
        return [preamble]
    return books


def parse_book(paragraphs: list[str], folder_rarity: str) -> BookDefinition:
    """Parse metadata and pages from extracted document paragraphs."""

    folder_rarity = normalize_rarity(folder_rarity)
    fields: dict[str, str] = {}
    page_paragraphs: list[str] = []
    page_section_started = False

    for paragraph in paragraphs:
        paragraph = _clean_paragraph(paragraph)
        if not paragraph:
            continue

        page_match = PAGE_PATTERN.match(paragraph)
        field_match = FIELD_PATTERN.match(paragraph)

        if not page_section_started and field_match:
            field = field_match.group(1).lower()
            value = _clean_paragraph(field_match.group(2))
            if field == "pages":
                # ``Pages:`` is a section heading. If text follows it, keep
                # that text as the first page rather than discarding authored content.
                page_section_started = True
                if value:
                    page_paragraphs.append(value)
                continue
            if field in fields:
                raise BookParseError(f"duplicate {field} field")
            fields[field] = value
            continue

        if page_match:
            page_section_started = True
        elif field_match and page_section_started:
            # A later Title/Author/etc. is ordinary page text once the page
            # section has started.
            pass
        page_paragraphs.append(paragraph)

    title = _validate_text(fields.get("title", ""), "Title", MAX_TITLE_LENGTH)
    author = _validate_text(fields.get("author", ""), "Author", MAX_AUTHOR_LENGTH)

    document_rarity = fields.get("rarity")
    rarity = folder_rarity
    if document_rarity:
        parsed_rarity = normalize_rarity(document_rarity)
        if parsed_rarity != folder_rarity:
            raise BookParseError(
                f"Rarity {document_rarity!r} conflicts with source folder {folder_rarity!r}"
            )
        rarity = parsed_rarity

    return BookDefinition(title, author, rarity, _parse_pages(page_paragraphs))


def parse_books(paragraphs: Iterable[str], folder_rarity: str) -> list[BookDefinition]:
    """Parse every book in one source document in document order."""

    return [
        parse_book(book_paragraphs, folder_rarity)
        for book_paragraphs in split_books(paragraphs)
    ]


def _write_definition(definition: BookDefinition, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(definition.as_json(), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def convert_file(source: Path, destination: Path, folder_rarity: str) -> BookDefinition:
    """Convert a source containing exactly one book to one JSON file."""

    definitions = parse_books(read_source(source), folder_rarity)
    if len(definitions) != 1:
        raise BookParseError(
            f"{source} contains {len(definitions)} books; use convert_all for multi-book sources"
        )
    _write_definition(definitions[0], destination)
    return definitions[0]


def _source_files(input_root: Path) -> list[tuple[Path, str]]:
    if not input_root.is_dir():
        raise BookParseError(f"book input directory does not exist: {input_root}")

    files: list[tuple[Path, str]] = []
    for source in sorted(input_root.rglob("*")):
        if not source.is_file() or source.suffix.lower() not in {".txt", ".docx"}:
            continue
        try:
            relative_parent = source.parent.relative_to(input_root)
        except ValueError as exc:
            raise BookParseError(f"source is outside input directory: {source}") from exc
        if len(relative_parent.parts) != 1:
            raise BookParseError(
                f"book {source} must be directly inside a rarity folder"
            )
        folder = relative_parent.parts[0]
        files.append((source, normalize_rarity(folder)))
    return files


def convert_all(input_root: Path = DEFAULT_INPUT, output_root: Path = DEFAULT_OUTPUT) -> int:
    files = _source_files(input_root)
    seen_outputs: set[Path] = set()
    converted = 0
    for source, folder_rarity in files:
        definitions = parse_books(read_source(source), folder_rarity)
        multi_book = len(definitions) > 1
        for number, definition in enumerate(definitions, start=1):
            if multi_book:
                filename = (
                    f"{slug(source.stem)}_{number:02d}_{slug(definition.title)}.json"
                )
            else:
                filename = f"{slug(source.stem)}.json"
            output = output_root / folder_rarity / filename
            if output in seen_outputs:
                raise BookParseError(f"two source books produce the same output: {output}")
            seen_outputs.add(output)
            _write_definition(definition, output)
            print(
                f"Converted {source} -> {output} "
                f"({definition.rarity}, {len(definition.pages)} pages)"
            )
            converted += 1
    if not files:
        print(f"No .txt or .docx books found under {input_root}")
    return converted


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    try:
        convert_all(args.input, args.output)
    except (BookParseError, OSError, UnicodeError) as exc:
        print(f"Book conversion failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
