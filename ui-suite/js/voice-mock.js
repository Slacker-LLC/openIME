/**
 * Voice Mock - AI 语音输入动态声波可视化与流式转写模拟
 */

export class VoiceVisualizer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.isRecording = false;
    this.animId = null;
    this.bars = 36;
    this.phase = 0;
  }

  start() {
    this.isRecording = true;
    this.render();
  }

  stop() {
    this.isRecording = false;
    if (this.animId) {
      cancelAnimationFrame(this.animId);
      this.animId = null;
    }
    this.clear();
  }

  clear() {
    const rect = this.canvas.getBoundingClientRect();
    this.ctx.clearRect(0, 0, rect.width, rect.height);
  }

  render() {
    if (!this.isRecording) return;
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = rect.width * dpr;
    this.canvas.height = rect.height * dpr;
    this.ctx.scale(dpr, dpr);

    const w = rect.width;
    const h = rect.height;
    this.ctx.clearRect(0, 0, w, h);

    const barWidth = w / (this.bars * 1.5);
    const gap = barWidth * 0.5;
    const isNeon = document.body.classList.contains('theme-cyberpunk');
    const isDark = document.body.classList.contains('theme-dark');

    this.phase += 0.08;

    for (let i = 0; i < this.bars; i++) {
      const x = i * (barWidth + gap) + (w - (this.bars * (barWidth + gap))) / 2;
      const wave = Math.sin(this.phase + i * 0.25) * Math.cos(this.phase * 0.7 + i * 0.15);
      const amp = (Math.abs(wave) * 0.7 + Math.random() * 0.3) * (h * 0.75);
      const barHeight = Math.max(amp, 6);
      const y = (h - barHeight) / 2;

      const grad = this.ctx.createLinearGradient(0, y, 0, y + barHeight);
      if (isNeon) {
        grad.addColorStop(0, '#00f0ff');
        grad.addColorStop(1, '#ff007f');
      } else if (isDark) {
        grad.addColorStop(0, '#60a5fa');
        grad.addColorStop(1, '#3b82f6');
      } else {
        grad.addColorStop(0, '#3b82f6');
        grad.addColorStop(1, '#1d4ed8');
      }

      this.ctx.fillStyle = grad;
      this.ctx.beginPath();
      this.ctx.roundRect(x, y, barWidth, barHeight, [barWidth / 2]);
      this.ctx.fill();
    }

    this.animId = requestAnimationFrame(() => this.render());
  }
}

export class VoiceSpeechSimulator {
  constructor(onTextChunk, onComplete) {
    this.onTextChunk = onTextChunk;
    this.onComplete = onComplete;
    this.timer = null;
    this.sampleSentences = {
      'mandarin': [
        '今天的天气非常不错，适合外出散步和工作。',
        '人工智能输入法正在为您提供毫秒级的极速语音转写。',
        '欢迎体验这套全功能输入法 UI 交互设计方案！',
        '项目进度正常，稍后我们会召开技术评审会议。'
      ],
      'cantonese': [
        '今日天氣真係好好，不如一齊去飲茶啦。',
        '多謝晒你嘅支持，我哋會繼續努力做好產品！'
      ],
      'english': [
        'Google DeepMind Antigravity provides next-gen agentic coding.',
        'This is a modern input method user interface suite built with pure web technologies.'
      ],
      'sichuan': [
        '巴适得板！今天中午咱们一起去整一顿地道的四川火锅！',
        '要得，这个界面做得很安逸，细节非常到位！'
      ]
    };
  }

  start(lang = 'mandarin') {
    this.stop();
    const list = this.sampleSentences[lang] || this.sampleSentences['mandarin'];
    const sentence = list[Math.floor(Math.random() * list.length)];
    let idx = 0;

    this.timer = setInterval(() => {
      if (idx < sentence.length) {
        idx++;
        const currentText = sentence.slice(0, idx);
        if (this.onTextChunk) this.onTextChunk(currentText);
      } else {
        this.stop();
        if (this.onComplete) this.onComplete(sentence);
      }
    }, 120);
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
