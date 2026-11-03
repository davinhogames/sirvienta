from __future__ import annotations

import uvicorn

from sirvienta.app import create_app
from sirvienta.config import get_config


if __name__ == "__main__":
    config = get_config()
    app = create_app()
    uvicorn.run(app, host="0.0.0.0", port=config.app_port, reload=False)
