

- pass in set of relevant source jars when looking for symbol, for BSP target


- write config with all BSP defaults, easier to override by user
- use paths relative to WORKSPACE, not absolute, easier to debug

- test mill
- check thread safety
- parse JAVAC -targetroot:${semanticdbDir}

New features:
- support hover info
- support for autocomplete
- support multi-root workspaces ? quite niche
