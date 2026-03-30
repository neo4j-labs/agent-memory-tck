# Root conftest — registers the --bridge-url CLI option.
# The tck/tests/conftest.py handles fixtures and markers.

def pytest_addoption(parser):
    """Add TCK-specific CLI options."""
    parser.addoption(
        "--bridge-url",
        default=None,
        help="URL of the HTTP bridge conformance server (e.g., http://localhost:3001). "
        "When set, uses HTTPBridgeAdapter instead of requiring a Python adapter fixture.",
    )
