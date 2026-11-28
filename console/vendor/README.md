# Vendored UI library packages

These tarballs are `@visual-ui/core`, `components`, `renderer` and `schema`
version 0.2.0 from the native-ui-library repository (`visual-ui-builder`),
built with `npm run pack:packages` there. The console installs them with
`file:vendor/<name>.tgz` dependencies so it never needs a private registry
for `@visual-ui`; `npm install`/`npm ci` still fetches the dev dependencies
from the public npm registry.

To upgrade: run `npm run pack:packages` in the library, copy the four files
from its `release/` folder here, update the file names in
`console/package.json` if the version changed, then `npm install` in `console/`.
