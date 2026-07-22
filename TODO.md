
- extract ScalacOptionsUtils, consolidate Url/Paths utils..

- improve nav experience:
    - index target sources as ASTs first, so user can navigate in open files (prioritize open files first!!)
    - after compile, parse semanticdb files, those override initial index entries
    - concurrently index JDK, and dependencies


- optimize parsing deps sources, serialize index?
- readonly deps files..
- test mill
- dont bother with compile etc if editor not in focus? e.g. when ai coding agent touches many files?
- check thread safety
- parse javac -targetroot:${semanticdbDir}
- recursively parse scala/java source bodies, imports etc
- store deps index in same place as target index
    - we need to get references in ALL targets from a dep source, e.g. pprint library or JDK

New features:
- support hover info
- support for autocomplete
- support multi-root workspaces ? quite niche
