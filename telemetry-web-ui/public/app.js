// Elements
const speedSlider = document.getElementById('speed-slider');
const speedValue = document.getElementById('speed-value');
const hrSlider = document.getElementById('hr-slider');
const hrValue = document.getElementById('hr-value');
const crashBtn = document.getElementById('crash-btn');
const dtcSelect = document.getElementById('dtc-select');
const toast = document.getElementById('toast');

// State
let debounceTimer;

// API Call
async function sendTelemetry(data) {
    try {
        const response = await fetch('http://localhost:3000/api/telemetry', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data)
        });
        
        if (response.ok) {
            showToast('Signal Transmitted');
        } else {
            showToast('Error Transmitting Signal', true);
        }
    } catch (error) {
        console.error('Error:', error);
        showToast('Connection Error', true);
    }
}

// Toast
function showToast(message, isError = false) {
    toast.textContent = message;
    toast.style.borderColor = isError ? 'var(--danger)' : 'var(--primary)';
    toast.style.color = isError ? 'var(--danger)' : 'var(--primary)';
    
    toast.classList.remove('hidden');
    
    setTimeout(() => {
        toast.classList.add('hidden');
    }, 2000);
}

// Debounce helper for sliders
function debouncedSend(data, delay = 300) {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
        sendTelemetry(data);
    }, delay);
}

// Event Listeners
speedSlider.addEventListener('input', (e) => {
    const val = e.target.value;
    speedValue.textContent = `${val} km/h`;
    debouncedSend({ speed: parseFloat(val) }, 200);
});

hrSlider.addEventListener('input', (e) => {
    const val = e.target.value;
    hrValue.textContent = `${val} bpm`;
    debouncedSend({ heartRate: parseInt(val) }, 200);
});

crashBtn.addEventListener('click', () => {
    // Add active animation
    crashBtn.style.transform = 'scale(0.9)';
    setTimeout(() => { crashBtn.style.transform = 'none'; }, 150);
    
    // Update speed slider to 0 visually
    speedSlider.value = 0;
    speedValue.textContent = '0 km/h';
    
    sendTelemetry({ crash: true, speed: 0 });
});

// Exposed globally for onclick in HTML
window.setSpeed = function(val) {
    speedSlider.value = val;
    speedValue.textContent = `${val} km/h`;
    sendTelemetry({ speed: val });
};

window.setHeartRate = function(val) {
    if(val === 0) {
        hrSlider.value = 40;
        hrValue.textContent = 'N/A';
    } else {
        hrSlider.value = val;
        hrValue.textContent = `${val} bpm`;
    }
    sendTelemetry({ heartRate: val === 0 ? -1 : val });
};

window.injectDtc = function() {
    const code = dtcSelect.value;
    sendTelemetry({ dtcCode: code, dtcClear: false });
};

window.clearDtc = function() {
    const code = dtcSelect.value;
    sendTelemetry({ dtcCode: code, dtcClear: true });
};
