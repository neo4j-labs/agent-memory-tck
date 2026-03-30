.PHONY: html pdf clean install-deps

ASCIIDOCTOR = asciidoctor
ASCIIDOCTOR_PDF = asciidoctor-pdf
OUT_DIR = build

# All .adoc files
ADOC_FILES = $(shell find . -name '*.adoc' -not -path './build/*')

html: $(ADOC_FILES)
	@mkdir -p $(OUT_DIR)/tutorials $(OUT_DIR)/how-to $(OUT_DIR)/reference $(OUT_DIR)/explanation
	@# Build index
	$(ASCIIDOCTOR) -D $(OUT_DIR) -a linkcss -a stylesheet=style.css index.adoc
	@# Build all sub-pages
	@for f in tutorials/*.adoc; do \
		[ -f "$$f" ] && $(ASCIIDOCTOR) -D $(OUT_DIR)/tutorials -a linkcss -a stylesheet=../style.css $$f || true; \
	done
	@for f in how-to/*.adoc; do \
		[ -f "$$f" ] && $(ASCIIDOCTOR) -D $(OUT_DIR)/how-to -a linkcss -a stylesheet=../style.css $$f || true; \
	done
	@for f in reference/*.adoc; do \
		[ -f "$$f" ] && $(ASCIIDOCTOR) -D $(OUT_DIR)/reference -a linkcss -a stylesheet=../style.css $$f || true; \
	done
	@for f in explanation/*.adoc; do \
		[ -f "$$f" ] && $(ASCIIDOCTOR) -D $(OUT_DIR)/explanation -a linkcss -a stylesheet=../style.css $$f || true; \
	done
	@# Copy stylesheet
	cp style.css $(OUT_DIR)/style.css
	@echo "Build complete: $(OUT_DIR)/"

pdf: index.adoc
	@mkdir -p $(OUT_DIR)
	$(ASCIIDOCTOR_PDF) -D $(OUT_DIR) index.adoc
	@echo "PDF generated: $(OUT_DIR)/index.pdf"

clean:
	rm -rf $(OUT_DIR)

install-deps:
	gem install asciidoctor asciidoctor-pdf rouge
