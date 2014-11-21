#!/bin/sh
mkdir -p target/generated-resources
asciidoctor \
	-v \
	-b html \
	-d book \
	-C \
	-B src/main/asciidoc/ \
	-D target/generated-resources \
	-a sourceDir=examples \
	-a attribute-missing=skip \
	-a allow-uri-read=allow-uri-read \
	-a attribute-undefined=drop-line \
	-a imagesdir=images \
	-a source-highlighter=coderay \
	src/main/asciidoc/index.adoc

