/**
 * Sound FX - Web Audio API 按键音效合成器 (机械键盘/iPhone轻打/打字机)
 */

export class SoundFX {
  constructor() {
    this.audioCtx = null;
    this.enabled = true;
    this.currentProfile = 'mechanical'; // 'mechanical' | 'soft' | 'typewriter'
  }

  init() {
    if (!this.audioCtx) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      if (AudioContext) {
        this.audioCtx = new AudioContext();
      }
    }
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume();
    }
  }

  playKeySound(type = 'standard') {
    if (!this.enabled) return;
    try {
      this.init();
      if (!this.audioCtx) return;

      const now = this.audioCtx.currentTime;

      if (this.currentProfile === 'mechanical') {
        // 模拟清脆机械青轴/茶轴音
        const osc = this.audioCtx.createOscillator();
        const gain = this.audioCtx.createGain();
        const filter = this.audioCtx.createBiquadFilter();

        osc.type = 'triangle';
        const baseFreq = type === 'space' ? 180 : (type === 'enter' ? 240 : (type === 'backspace' ? 300 : 420 + Math.random() * 80));
        osc.frequency.setValueAtTime(baseFreq, now);
        osc.frequency.exponentialRampToValueAtTime(80, now + 0.04);

        filter.type = 'lowpass';
        filter.frequency.setValueAtTime(3200, now);

        gain.gain.setValueAtTime(0.3, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.045);

        osc.connect(filter);
        filter.connect(gain);
        gain.connect(this.audioCtx.destination);

        osc.start(now);
        osc.stop(now + 0.05);

        // 叠加微弱白噪声模拟敲击声
        const bufferSize = this.audioCtx.sampleRate * 0.02;
        const buffer = this.audioCtx.createBuffer(1, bufferSize, this.audioCtx.sampleRate);
        const data = buffer.getChannelData(0);
        for (let i = 0; i < bufferSize; i++) {
          data[i] = (Math.random() * 2 - 1) * Math.exp(-i / (bufferSize * 0.2));
        }
        const noise = this.audioCtx.createBufferSource();
        noise.buffer = buffer;
        const noiseGain = this.audioCtx.createGain();
        noiseGain.gain.setValueAtTime(0.12, now);
        noiseGain.gain.exponentialRampToValueAtTime(0.001, now + 0.02);
        noise.connect(noiseGain);
        noiseGain.connect(this.audioCtx.destination);
        noise.start(now);

      } else if (this.currentProfile === 'soft') {
        // 模拟 iPhone 软触感“哒”声
        const osc = this.audioCtx.createOscillator();
        const gain = this.audioCtx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(300, now);
        osc.frequency.exponentialRampToValueAtTime(90, now + 0.03);

        gain.gain.setValueAtTime(0.2, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.03);

        osc.connect(gain);
        gain.connect(this.audioCtx.destination);
        osc.start(now);
        osc.stop(now + 0.035);
      }
    } catch (e) {
      console.warn('Audio FX play error', e);
    }
  }
}
