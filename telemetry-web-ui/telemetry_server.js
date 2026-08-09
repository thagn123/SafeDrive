const express = require('express');
const { execFile } = require('child_process');
const path = require('path');

const app = express();
const PORT = 3000;
const HOST = '127.0.0.1';
const rootDir = path.resolve(__dirname, '..');
const telemetryScript = path.join(rootDir, 'mock_vehicle_telemetry.ps1');

app.use(express.json({ limit: '8kb' }));
app.use(express.static(path.join(__dirname, 'public')));

function runFile(file, args, timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
        execFile(file, args, { cwd: rootDir, timeout: timeoutMs, windowsHide: true }, (error, stdout, stderr) => {
            if (error) {
                reject(new Error((stderr || error.message).trim()));
                return;
            }
            resolve(stdout.trim());
        });
    });
}

function validateTelemetry(body) {
    const command = {};
    if (body.speed !== undefined && body.speed !== null) {
        if (typeof body.speed !== 'number' || !Number.isFinite(body.speed) || body.speed < 0 || body.speed > 300) {
            throw new Error('speed must be a number from 0 to 300');
        }
        command.speed = body.speed;
    }
    if (body.crash !== undefined) {
        if (typeof body.crash !== 'boolean') throw new Error('crash must be boolean');
        command.crash = body.crash;
    }
    if (body.heartRate !== undefined && body.heartRate !== null) {
        if (!Number.isInteger(body.heartRate) || (body.heartRate !== -1 && (body.heartRate < 20 || body.heartRate > 250))) {
            throw new Error('heartRate must be -1 or an integer from 20 to 250');
        }
        command.heartRate = body.heartRate;
    }
    if (body.dtcCode !== undefined && body.dtcCode !== null && body.dtcCode !== '') {
        if (typeof body.dtcCode !== 'string' || !/^[A-Z0-9_]{1,64}$/.test(body.dtcCode)) {
            throw new Error('dtcCode contains unsupported characters');
        }
        command.dtcCode = body.dtcCode;
        command.dtcClear = body.dtcClear === true;
    }
    if (Object.keys(command).length === 0) throw new Error('no telemetry field supplied');
    return command;
}

app.get('/api/status', async (_req, res) => {
    try {
        const output = await runFile('adb.exe', ['devices'], 5000);
        const devices = output.split(/\r?\n/).slice(1).filter(line => /\sdevice$/.test(line));
        res.json({ connected: devices.length === 1, deviceCount: devices.length });
    } catch (error) {
        res.status(503).json({ connected: false, deviceCount: 0, error: error.message });
    }
});

app.post('/api/telemetry', async (req, res) => {
    try {
        const command = validateTelemetry(req.body || {});
        const args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', telemetryScript];
        if (command.speed !== undefined) args.push('-Speed', String(command.speed));
        if (command.crash === true) args.push('-Crash');
        if (command.crash === false) args.push('-ClearCrash');
        if (command.heartRate !== undefined) args.push('-HeartRate', String(command.heartRate));
        if (command.dtcCode) {
            args.push('-DtcCode', command.dtcCode);
            if (command.dtcClear) args.push('-DtcClear');
        }
        const output = await runFile('powershell.exe', args);
        res.json({ success: true, output });
    } catch (error) {
        const status = /must|unsupported|supplied/.test(error.message) ? 400 : 500;
        res.status(status).json({ success: false, error: error.message });
    }
});

app.listen(PORT, HOST, () => {
    console.log(`Telemetry Server running at http://${HOST}:${PORT}`);
    console.log(`Serving Web UI from public/ directory.`);
});
