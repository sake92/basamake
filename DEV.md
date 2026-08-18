
deder exec -t assembly -m modules-lsp && cp .deder/out/modules-lsp/assembly/out.jar ../basamake-vscode/basamake.jar


VERSION=v0.2.0 && git tag -a $VERSION -m "$VERSION" && git push origin $VERSION




