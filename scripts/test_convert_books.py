#!/usr/bin/env python3
"""Focused tests for the authored-book converter."""

from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from pathlib import Path

try:
    from scripts.convert_books import (
        BookParseError,
        convert_all,
        convert_file,
        parse_book,
        parse_books,
    )
except ModuleNotFoundError:  # Running this file directly from the scripts folder.
    from convert_books import BookParseError, convert_all, convert_file, parse_book, parse_books


class ConvertBooksTest(unittest.TestCase):
    def test_page_markers_split_pages_and_folder_supplies_rarity(self) -> None:
        definition = parse_book(
            [
                "Title: A Test Book",
                "Author: Ada",
                "Page [1]: First page",
                "Page 2: Second page",
            ],
            "village_manger",
        )
        self.assertEqual("village_manager", definition.rarity)
        self.assertEqual(("First page", "Second page"), definition.pages)

    def test_written_page_numbers_are_case_insensitive(self) -> None:
        definition = parse_book(
            [
                "Title: A Test Book",
                "Author: Ada",
                "Page One: First page",
                "Page one: Second page",
                "Page THREE: Third page",
                "Page Twenty-One: Fourth page",
            ],
            "common",
        )
        self.assertEqual(
            ("First page", "Second page", "Third page", "Fourth page"),
            definition.pages,
        )

    def test_title_and_author_limits_are_enforced(self) -> None:
        with self.assertRaisesRegex(BookParseError, "Title is longer than 32"):
            parse_book(
                [
                    "Title: " + ("T" * 33),
                    "Author: Ada",
                    "Page 1: Content",
                ],
                "common",
            )
        with self.assertRaisesRegex(BookParseError, "Author is longer than 128"):
            parse_book(
                [
                    "Title: Book",
                    "Author: " + ("A" * 129),
                    "Page 1: Content",
                ],
                "common",
            )

    def test_default_paths_are_relative_to_repository(self) -> None:
        from scripts.convert_books import DEFAULT_INPUT, DEFAULT_OUTPUT, REPOSITORY_ROOT

        self.assertEqual(REPOSITORY_ROOT / "docs/lore/books", DEFAULT_INPUT)
        self.assertEqual(
            REPOSITORY_ROOT
            / "src/main/resources/data/emeraldcapitalism/library_books",
            DEFAULT_OUTPUT,
        )

    def test_unmarked_paragraphs_are_skipped_instead_of_pages(self) -> None:
        with self.assertRaisesRegex(BookParseError, "no pages were found"):
            parse_book(
                [
                    "Title: A Test Book",
                    "Author: Ada",
                    "Rarity: Common",
                    "First paragraph",
                    "Second paragraph",
                ],
                "common",
            )

    def test_unmarked_lines_after_page_marker_continue_current_page(self) -> None:
        definition = parse_book(
            [
                "Title: A Test Book",
                "Author: Ada",
                "Page 1: First line",
                "Second line",
                "",
            ],
            "common",
        )
        self.assertEqual(("First line\nSecond line",), definition.pages)
        self.assertEqual("static", definition.type)

    def test_game_data_type_is_normalized(self) -> None:
        definition = parse_book(
            [
                "Title: A Grave Message",
                "Author: Sairviv",
                "Rarity: Legendary",
                "Type: Steve Grave Location",
                "Page 1: The answer is at {{steve_grave_coordinates}}.",
            ],
            "legendary",
        )
        self.assertEqual("steve_grave_location", definition.type)
        self.assertEqual(
            "steve_grave_location",
            definition.as_json()["type"],
        )

    def test_new_title_starts_a_new_book(self) -> None:
        definitions = parse_books(
            [
                "Title: First Book",
                "Author: Ada",
                "Page 1: First page",
                "Title: Second Book",
                "Author: Bob",
                "Page 1: Second book paragraph one",
                "Second book paragraph two",
            ],
            "common",
        )
        self.assertEqual(["First Book", "Second Book"], [book.title for book in definitions])
        self.assertEqual(
            [
                ("First page",),
                ("Second book paragraph one\nSecond book paragraph two",),
            ],
            [book.pages for book in definitions],
        )

    def test_multi_book_source_writes_one_json_per_book(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_root = root / "books"
            source = input_root / "common" / "anthology.txt"
            source.parent.mkdir(parents=True)
            source.write_text(
                "Title: First Book\n"
                "Author: Ada\n"
                "Page 1: First page\n"
                "Title: Second Book\n"
                "Author: Bob\n"
                "Page 1: Second page\n",
                encoding="utf-8",
            )
            output_root = root / "generated"
            self.assertEqual(2, convert_all(input_root, output_root))
            generated = sorted(output_root.glob("common/*.json"))
            self.assertEqual(
                ["anthology_01_first_book.json", "anthology_02_second_book.json"],
                [path.name for path in generated],
            )
            self.assertEqual(
                "Second Book",
                json.loads(generated[1].read_text(encoding="utf-8"))["title"],
            )

    def test_rerun_refreshes_books_and_removes_stale_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_root = root / "books"
            source = input_root / "legendary" / "ANSWERS.txt"
            source.parent.mkdir(parents=True)
            source.write_text(
                "Title: ANSWERS\n"
                "Author: Sairviv\n"
                "Page 1: Original answer.\n",
                encoding="utf-8",
            )
            output_root = root / "generated"
            old_output = output_root / "legendary" / "a_message_from_sairviv.json"
            old_output.parent.mkdir(parents=True)
            old_output.write_text("{}\n", encoding="utf-8")

            self.assertEqual(1, convert_all(input_root, output_root))
            generated = output_root / "legendary" / "answers.json"
            self.assertEqual(
                "ANSWERS",
                json.loads(generated.read_text(encoding="utf-8"))["title"],
            )
            self.assertFalse(old_output.exists())

            source.write_text(
                "Title: ANSWERS\n"
                "Author: Sairviv\n"
                "Page 1: Updated answer.\n",
                encoding="utf-8",
            )
            self.assertEqual(1, convert_all(input_root, output_root))
            self.assertEqual(
                ["Updated answer."],
                json.loads(generated.read_text(encoding="utf-8"))["pages"],
            )

    def test_input_and_output_overlap_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_root = root / "books"
            source = input_root / "common" / "book.txt"
            source.parent.mkdir(parents=True)
            source.write_text(
                "Title: Book\nAuthor: Ada\nPage 1: Content\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(BookParseError, "must not overlap"):
                convert_all(input_root, input_root / "generated")
            with self.assertRaisesRegex(BookParseError, "must not overlap"):
                convert_all(input_root, root)

    def test_docx_is_converted_without_python_docx(self) -> None:
        document_xml = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            "<w:body>"
            "<w:p><w:r><w:t>Title: Word Book</w:t></w:r></w:p>"
            "<w:p><w:r><w:t>Author: Ada</w:t></w:r></w:p>"
            "<w:p><w:r><w:t>Page 1: Written in Word.</w:t></w:r></w:p>"
            "</w:body></w:document>"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "book.docx"
            output = root / "out.json"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("word/document.xml", document_xml)
            convert_file(source, output, "rare")
            payload = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual("Word Book", payload["title"])
        self.assertEqual("rare", payload["rarity"])
        self.assertEqual(["Written in Word."], payload["pages"])

    def test_explicit_rarity_must_match_folder(self) -> None:
        with self.assertRaisesRegex(BookParseError, "conflicts"):
            parse_book(
                ["Title: Book", "Author: Ada", "Rarity: Rare", "Content"],
                "common",
            )

    def test_missing_required_field_is_rejected(self) -> None:
        with self.assertRaisesRegex(BookParseError, "Author is empty"):
            parse_book(["Title: Book", "Content"], "common")


if __name__ == "__main__":
    unittest.main()
