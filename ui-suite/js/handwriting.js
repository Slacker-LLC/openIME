/**
 * Handwriting Engine - Canvas 平滑手写轨迹引擎与模拟识别
 */

export class HandwritingEngine {
  constructor(canvas, onCandidatesUpdate, onStrokeEnd) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.onCandidatesUpdate = onCandidatesUpdate;
    this.onStrokeEnd = onStrokeEnd;

    this.isDrawing = false;
    this.strokes = []; // Array of array of points
    this.currentStroke = [];
    this.strokeCount = 0;

    // 预置常见汉字笔画数与候选字映射
    this.recognitionMap = {
      1: ['一', '乙', '丨', '亅', '丿', '乀', '乚', '乛'],
      2: ['二', '十', '人', '入', '八', '几', '儿', '了', '力', '乃', '刀', '又', '七', '卜', '厂'],
      3: ['三', '大', '小', '口', '山', '川', '工', '上', '下', '广', '门', '飞', '子', '女', '马', '么', '个', '也', '夕'],
      4: ['中', '文', '王', '天', '太', '日', '月', '水', '火', '手', '木', '心', '开', '不', '友', '车', '风', '方', '分', '牛'],
      5: ['你', '好', '正', '生', '用', '白', '田', '目', '石', '示', '立', '电', '出', '半', '平', '冬', '包', '打', '北', '本'],
      6: ['全', '同', '多', '光', '安', '年', '老', '西', '早', '自', '会', '各', '名', '米', '回', '向', '如', '成', '江', '字'],
      7: ['学', '作', '我', '体', '何', '里', '来', '步', '身', '见', '连', '完', '近', '张', '别', '极', '画', '希', '局', '君'],
      8: ['国', '明', '果', '和', '金', '定', '法', '林', '服', '雨', '青', '表', '物', '事', '现', '直', '知', '使', '所', '非'],
      9: ['春', '点', '重', '信', '南', '秋', '省', '星', '思', '要', '政', '指', '查', '美', '带', '便', '保', '音', '度', '看'],
      10: ['高', '海', '特', '原', '家', '笑', '真', '能', '校', '通', '流', '乘', '病', '爱', '班', '钱', '倒', '拿', '起', '座']
    };

    this.initCanvas();
    this.bindEvents();
  }

  initCanvas() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = (rect.width || 360) * dpr;
    this.canvas.height = (rect.height || 220) * dpr;
    this.ctx.scale(dpr, dpr);
    this.ctx.lineCap = 'round';
    this.ctx.lineJoin = 'round';
    this.redraw();
  }

  bindEvents() {
    const getPos = (e) => {
      const rect = this.canvas.getBoundingClientRect();
      const clientX = e.touches ? e.touches[0].clientX : e.clientX;
      const clientY = e.touches ? e.touches[0].clientY : e.clientY;
      return {
        x: clientX - rect.left,
        y: clientY - rect.top,
        time: Date.now()
      };
    };

    const startDraw = (e) => {
      e.preventDefault();
      this.isDrawing = true;
      const pos = getPos(e);
      this.currentStroke = [pos];
      this.drawPoint(pos.x, pos.y);
    };

    const moveDraw = (e) => {
      if (!this.isDrawing) return;
      e.preventDefault();
      const pos = getPos(e);
      this.currentStroke.push(pos);
      this.drawSmoothStroke(this.currentStroke);
    };

    const endDraw = (e) => {
      if (!this.isDrawing) return;
      e.preventDefault();
      this.isDrawing = false;
      if (this.currentStroke.length > 0) {
        this.strokes.push([...this.currentStroke]);
        this.currentStroke = [];
        this.strokeCount = this.strokes.length;
        this.recognize();
        if (this.onStrokeEnd) this.onStrokeEnd(this.strokeCount);
      }
    };

    this.canvas.addEventListener('mousedown', startDraw);
    this.canvas.addEventListener('mousemove', moveDraw);
    window.addEventListener('mouseup', endDraw);

    this.canvas.addEventListener('touchstart', startDraw, { passive: false });
    this.canvas.addEventListener('touchmove', moveDraw, { passive: false });
    window.addEventListener('touchend', endDraw, { passive: false });
  }

  drawPoint(x, y) {
    this.ctx.fillStyle = '#222';
    if (document.body.classList.contains('theme-dark') || document.body.classList.contains('theme-cyberpunk')) {
      this.ctx.fillStyle = '#00ffc4';
    }
    this.ctx.beginPath();
    this.ctx.arc(x, y, 3, 0, Math.PI * 2);
    this.ctx.fill();
  }

  drawSmoothStroke(points) {
    if (points.length < 2) return;
    const isDark = document.body.classList.contains('theme-dark') || document.body.classList.contains('theme-cyberpunk');
    this.ctx.strokeStyle = isDark ? '#4ade80' : '#1f2937';
    this.ctx.lineWidth = 5;

    this.ctx.beginPath();
    this.ctx.moveTo(points[0].x, points[0].y);

    for (let i = 1; i < points.length - 1; i++) {
      const xc = (points[i].x + points[i + 1].x) / 2;
      const yc = (points[i].y + points[i + 1].y) / 2;
      this.ctx.quadraticCurveTo(points[i].x, points[i].y, xc, yc);
    }
    const last = points[points.length - 1];
    this.ctx.lineTo(last.x, last.y);
    this.ctx.stroke();
  }

  redraw() {
    const rect = this.canvas.getBoundingClientRect();
    this.ctx.clearRect(0, 0, rect.width, rect.height);

    // 绘制九宫米字格辅助参考线
    this.drawGrid(rect.width, rect.height);

    // 重绘所有保存的笔迹
    this.strokes.forEach(stroke => {
      this.drawSmoothStroke(stroke);
    });
  }

  drawGrid(w, h) {
    this.ctx.save();
    this.ctx.strokeStyle = 'rgba(150, 150, 150, 0.15)';
    this.ctx.lineWidth = 1;
    this.ctx.setLineDash([4, 4]);

    // 十字虚线
    this.ctx.beginPath();
    this.ctx.moveTo(w / 2, 0);
    this.ctx.lineTo(w / 2, h);
    this.ctx.moveTo(0, h / 2);
    this.ctx.lineTo(w, h / 2);

    // 对角虚线
    this.ctx.moveTo(0, 0);
    this.ctx.lineTo(w, h);
    this.ctx.moveTo(w, 0);
    this.ctx.lineTo(0, h);
    this.ctx.stroke();
    this.ctx.restore();
  }

  recognize() {
    const count = Math.min(Math.max(this.strokes.length, 1), 10);
    const results = this.recognitionMap[count] || ['字', '汉', '手', '写', '输', '入'];
    if (this.onCandidatesUpdate) {
      this.onCandidatesUpdate(results);
    }
  }

  undo() {
    if (this.strokes.length > 0) {
      this.strokes.pop();
      this.strokeCount = this.strokes.length;
      this.redraw();
      if (this.strokeCount > 0) {
        this.recognize();
      } else {
        if (this.onCandidatesUpdate) this.onCandidatesUpdate([]);
      }
    }
  }

  clear() {
    this.strokes = [];
    this.strokeCount = 0;
    this.redraw();
    if (this.onCandidatesUpdate) this.onCandidatesUpdate([]);
  }
}
