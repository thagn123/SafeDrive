const express = require('express');
const bodyParser = require('body-parser');
const cors = require('cors');
const { exec } = require('child_process');
const path = require('path');

const app = express();
const PORT = 3000;

app.use(cors());
app.use(bodyParser.json());
app.use(express.static('public'));

app.post('/api/telemetry', (req, res) => {
    const { speed, crash, heartRate, dtcCode, dtcClear } = req.body;
    let cmd = 'powershell.exe -ExecutionPolicy Bypass -File ../mock_vehicle_telemetry.ps1';

    if (speed !== undefined && speed !== null) {
        cmd += ` -Speed ${speed}`;
    }
    if (crash) {
        cmd += ` -Crash`;
    }
    if (heartRate !== undefined && heartRate !== null) {
        cmd += ` -HeartRate ${heartRate}`;
    }
    if (dtcCode) {
        cmd += ` -DtcCode "${dtcCode}"`;
        if (dtcClear) {
            cmd += ` -DtcClear`;
        }
    }

    console.log(`Executing: ${cmd}`);
    exec(cmd, (error, stdout, stderr) => {
        if (error) {
            console.error(`Error: ${error.message}`);
            return res.status(500).json({ success: false, error: error.message });
        }
        res.json({ success: true, output: stdout });
    });
});

app.listen(PORT, () => {
    console.log(`Telemetry Server running at http://localhost:${PORT}`);
    console.log(`Serving Web UI from public/ directory.`);
});
