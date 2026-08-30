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

Page 1: The first page of the book.
Page 2: The second page of the book.
```

`Title` and `Author` are required. `Rarity` may be omitted when the source
folder supplies it. `Pages:` may be used as a section heading. When `Page N`
markers are present, they delimit pages; otherwise every non-empty paragraph
after the metadata becomes one page in order. In a multi-book file, every new
`Title` field ends the current book and starts the next one. Books generated
from a multi-book source receive filenames containing the source name, book
number, and title.

Run the converter from the repository root:

```text
python scripts/convert_books.py
```

Generated JSON is written to
`src/main/resources/data/emeraldcapitalism/library_books/`. The library
generator uses the 70/23/6/1 pool for Common, Uncommon, Rare, and Legendary
books. Bank Rule and Village Manager books are loaded as deterministic special
books and are not selected by the random library pool.

The `Enable Books in Creative Tab` setting is disabled by default. When enabled,
all loaded authored books are added to the mod's creative tab. The recommended
way to discover books is to explore the generated world and find them in village
libraries.
