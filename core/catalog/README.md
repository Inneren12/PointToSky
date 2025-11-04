# Core catalog module

Binary catalogs power the `SkyCatalog` and `ConstellationBoundaries` implementations that ship with the app.

- Data sources: Bright Star Catalog v5 (HYG) and IAU constellation boundaries.
- Attribution and license requirements still need to be double-checked. // TODO: проверить лицензионные требования и добавить точные формулировки
- Large production binaries (`stars_v1.bin`, `const_v1.bin`) are produced by CI tooling. Keep them as build artifacts or store them via Git LFS if their size exceeds the repository policy.

See [`NOTICE.md`](../../NOTICE.md) for the full notice.
