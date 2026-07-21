# loggly-mcp — build / install / register workflows for the stdio MCP server.
#
# Targets:
#   make jar         — build the self-executing fat jar (target/loggly-mcp-exec.jar)
#   make install     — copy the executable jar into $(INSTALL_DIR)
#   make register    — register the installed jar with Claude Code (user scope)
#   make unregister  — remove the server from the Claude Code user config
#   make ship        — jar + install + register, in one shot

# Load local secrets (LOGGLY_API_TOKEN=..., LOGGLY_SUBDOMAIN=...) from .env if present,
# and export them so they reach the `claude mcp add` invocation in the register target.
-include .env
export LOGGLY_API_TOKEN
export LOGGLY_SUBDOMAIN

# Where `make install` drops the binary. The MCP-client convention here is to
# stash all stdio MCP server binaries under ~/bin/mcp/ so they're easy to wire up.
INSTALL_DIR ?= $(HOME)/bin/mcp

# Name to register the server under in the MCP client.
MCP_SERVER_NAME  ?= loggly

# Default subdomain if none supplied via .env / environment (just a placeholder).
LOGGLY_SUBDOMAIN ?= acme

ARTIFACT_NAME    := loggly-mcp
BOOT_JAR         := target/$(ARTIFACT_NAME).jar
EXEC_JAR         := target/$(ARTIFACT_NAME)-exec.jar
INSTALLED_BINARY := $(INSTALL_DIR)/$(ARTIFACT_NAME).jar

.PHONY: help
help: ## list targets
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  %-12s %s\n", $$1, $$2}'

.PHONY: jar
jar: ## build the self-executing fat jar
	./mvnw -q clean package
	cat scripts/jar-prefix.sh $(BOOT_JAR) > $(EXEC_JAR)
	chmod +x $(EXEC_JAR)
	@echo "✓ $(EXEC_JAR)  ($$(du -h $(EXEC_JAR) | cut -f1))"

.PHONY: install
install: ## copy the executable jar into $(INSTALL_DIR)
	@test -f $(EXEC_JAR) || (echo "✗ $(EXEC_JAR) not found — run 'make jar' first" >&2; exit 1)
	@mkdir -p $(INSTALL_DIR)
	cp $(EXEC_JAR) $(INSTALLED_BINARY)
	chmod +x $(INSTALLED_BINARY)
	@echo "✓ installed: $(INSTALLED_BINARY)"

.PHONY: register
register: ## register the installed jar with Claude Code (user scope) incl. LOGGLY_* env
	@test -x $(INSTALLED_BINARY) || (echo "✗ $(INSTALLED_BINARY) not found — run 'make install' first" >&2; exit 1)
	@test -n "$(LOGGLY_API_TOKEN)" || (echo "✗ LOGGLY_API_TOKEN not set — add it to .env (see .env.example)" >&2; exit 1)
	@test -n "$(LOGGLY_SUBDOMAIN)" || (echo "✗ LOGGLY_SUBDOMAIN not set — add it to .env (see .env.example)" >&2; exit 1)
	-claude mcp remove --scope user $(MCP_SERVER_NAME) 2>/dev/null
	claude mcp add --scope user $(MCP_SERVER_NAME) \
		--env LOGGLY_API_TOKEN=$(LOGGLY_API_TOKEN) \
		--env LOGGLY_SUBDOMAIN=$(LOGGLY_SUBDOMAIN) \
		-- $(INSTALLED_BINARY)
	@claude mcp get $(MCP_SERVER_NAME) 2>/dev/null || claude mcp list 2>/dev/null | grep $(MCP_SERVER_NAME) || true

.PHONY: unregister
unregister: ## remove the server from the Claude Code user config
	-claude mcp remove --scope user $(MCP_SERVER_NAME)

.PHONY: ship
ship: jar install register ## jar + install + register, in one shot
