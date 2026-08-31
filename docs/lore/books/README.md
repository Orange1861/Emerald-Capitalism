# Authored Books

Each `.txt` or `.docx` file in this directory can contain one or more books.
Put it directly in the folder for its type:

- `common`
- `uncommon`
- `rare`
- `legendary`
- `bank_rules`
- `village_manager`

`village_manger` is also accepted as an input alias for older filenames or
folders, but `village_manager` is the canonical spelling.

Use one metadata field per paragraph. The converter looks for the text to the
right of the field name until the paragraph break:

```text
Title: The Village Ledger
Author: The First Clerk
Rarity: Common
Type: Static

Page 1: The first page of the book.
Page 2: The second page of the book.
```

`Title` and `Author` are required. Titles may be at most 32 characters and
authors may be at most 128 characters, including spaces and punctuation.
`Rarity` may be omitted when the source folder supplies it. `Pages:` may be
used as a section heading. `Page N`
markers delimit pages; `N` may be a digit or a written number such as `One`,
`one`, or `Twenty-One`. Lines before the first marker are skipped, and later
unmarked lines continue the current page. Each book must contain at least one
page marker. In a multi-book file, every new `Title` field ends the current
book and starts the next one. Books generated from a multi-book source receive
filenames containing the source name, book number, and title.

`Type` defaults to `Static`. The `Steve Grave Location` type replaces
`{{steve_grave_coordinates}}` in page text with the generated grave structure's
X/Y/Z origin coordinates when the book is placed in a village library. If the
grave cannot be resolved yet, it uses `[coordinates unavailable]` until the
book is opened again after the grave target becomes available.

Run the converter from the repository root:

```text
py scripts/convert_books.py
```

The same command also works from the `scripts` directory:

```text
py convert_books.py
```

The converter resolves its default input and output paths from the repository
location, not from the current working directory.

Generated JSON is written to
`src/main/resources/data/emeraldcapitalism/library_books/`.
The converter scans every `.txt` and `.docx` source, rewrites all matching
JSON definitions, and removes stale generated JSON files after all sources
parse successfully. Treat the output directory as generated data. To target a
different version, pass its output directory with `--output`. The library
generator uses the 70/23/6/1 pool for Common, Uncommon, Rare, and Legendary
books. Bank Rule and Village Manager books are loaded as deterministic special
books and are not selected by the random library pool.

The converter never deletes source books. It rejects an input and output path
that overlap before doing any work, so do not set `--output` to `docs/lore/books`
or to the repository root.

The `Enable Books in Creative Tab` setting is disabled by default. When enabled,
all loaded authored books are added to the mod's creative tab. The recommended
way to discover books is to explore the generated world and find them in village
libraries.
