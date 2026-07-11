class PCMProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.buffer = new Float32Array(960);
        this.bufferIndex = 0;
        this.port.onmessage = (e) => {
            if (e.data === 'start') this.active = true;
            if (e.data === 'stop') this.active = false;
        };
        this.active = true;
    }

    process(inputs, outputs, parameters) {
        if (!this.active || !inputs[0] || !inputs[0][0]) return true;

        const input = inputs[0][0];
        for (let i = 0; i < input.length; i++) {
            this.buffer[this.bufferIndex++] = input[i];
            if (this.bufferIndex >= 960) {
                const copy = this.buffer.slice(0);
                this.port.postMessage(copy, [copy.buffer]);
                this.bufferIndex = 0;
            }
        }
        return true;
    }
}

registerProcessor('pcm-processor', PCMProcessor);
