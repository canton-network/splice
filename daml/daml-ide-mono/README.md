# What is this?

This directory, along with its fake `daml.yaml` can be populated with symlinks by
`./scripts/setup-mono-package.sh` to serve as a fake single-dar package containing all the daml in
Splice such that cross-package changes can be worked on with a tighter feedback loop using the
Daml Language Server (LSP) in VS Code or other editors, e.g. by running `dpm studio` in this directory
after having run the script.

You'll also have to remember to re-run the script if you add new `.daml` files to any package.

You should still verify that everything builds/tests for real once you're done of course, but this mode
of interaction can be very useful to eliminate 12-20 second build cycles and instead get immediate
feedback from the VS Code extension when working on tests and making changes that would otherwise be in
their upstream DAR dependencies, for example.

