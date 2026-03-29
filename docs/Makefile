.PHONY: html pdf clean

ASCIIDOCTOR = asciidoctor
ASCIIDOCTOR_PDF = asciidoctor-pdf
SRC_DIR = .
OUT_DIR = build
ADOC_FILES = $(wildcard $(SRC_DIR)/*.adoc) \
             $(wildcard $(SRC_DIR)/tutorials/*.adoc) \
             $(wildcard $(SRC_DIR)/how-to/*.adoc) \
             $(wildcard $(SRC_DIR)/reference/*.adoc) \
             $(wildcard $(SRC_DIR)/explanation/*.adoc)

html: $(ADOC_FILES)
	@mkdir -p $(OUT_DIR)
	$(ASCIIDOCTOR) -D $(OUT_DIR) index.adoc
	@for f in tutorials/*.adoc how-to/*.adoc reference/*.adoc explanation/*.adoc; do \
		[ -f "$$f" ] && $(ASCIIDOCTOR) -D $(OUT_DIR)/$$(dirname $$f) $$f || true; \
	done
	@echo "Build complete: $(OUT_DIR)/"

pdf: index.adoc
	@mkdir -p $(OUT_DIR)
	$(ASCIIDOCTOR_PDF) -D $(OUT_DIR) index.adoc
	@echo "PDF generated: $(OUT_DIR)/index.pdf"

clean:
	rm -rf $(OUT_DIR)

# Install asciidoctor if not present
install-deps:
	gem install asciidoctor asciidoctor-pdf
