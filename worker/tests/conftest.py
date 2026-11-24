"""The collector imports `alinha_job_consumer` (installed next to it in the worker image). In tests, use the main
repo's copy: this plugin is always checked out as plugins/provider/external-surface of the main repo."""
import sys
from pathlib import Path

JOB_CONSUMER_SRC = Path(__file__).resolve().parents[5] / "workers" / "job_consumer" / "src"
if not JOB_CONSUMER_SRC.is_dir():
    raise RuntimeError(f"{JOB_CONSUMER_SRC} not found: run the tests from the main repo checkout")
sys.path.insert(0, str(JOB_CONSUMER_SRC))
sys.path.insert(0, str(Path(__file__).resolve().parent))
