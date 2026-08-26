/**
 * Keyboard View - 核心键盘视图渲染器
 * 核心包含 5 大键位：26键中文、26键英文、九键中文、九键英文(T9)、数字键盘
 * 支持正常固定模式 (Docked) 与 游戏悬浮形态 (Floating Game Mode)
 */

export class KeyboardView {
  constructor(container, options = {}) {
    this.container = container;
    this.options = options;
    this.onKeyPress = options.onKeyPress || (() => {});
    this.onCandidateSelect = options.onCandidateSelect || (() => {});
    this.onAction = options.onAction || (() => {});

    this.mode = 'pinyin26'; // 'pinyin26' | 'english26' | 'pinyin9' | 'english9' | 'digits'
    this.isFloating = false; // 是否处于游戏悬浮形态
    this.isShift = false;
    this.isCapsLock = false;
    this.composition = ''; // 当前拼写或T9数字串
    this.candidates = [];
    this.pinyin9Filters = [];
    this.selected9Filter = '';

    this.init();
    this.bindContainerEvents();
  }

  init() {
    this.render();
  }

  setMode(mode) {
    this.mode = mode;
    this.composition = '';
    this.candidates = [];
    this.render();
  }

  toggleFloating() {
    this.isFloating = !this.isFloating;
    this.render();
  }

  setComposition(comp, candidates = [], filters9 = []) {
    this.composition = comp;
    this.candidates = candidates;
    this.pinyin9Filters = filters9;
    this.updateCandidateBar();
    if (this.mode === 'pinyin9' || this.mode === 'english9') {
      this.update9KeyFilters();
    }
  }

  getModeName() {
    switch (this.mode) {
      case 'pinyin26': return '中文26键';
      case 'english26': return '英文26键';
      case 'pinyin9': return '中文九键';
      case 'english9': return '英文九键';
      case 'digits': return '数字键盘';
      default: return '中文26键';
    }
  }

  renderCandidateItems() {
    if (this.candidates.length === 0) {
      if (!this.composition) {
        return `<span class="candidate-empty-hint">输入开始打字...</span>`;
      }
      return `<span class="candidate-empty-hint">${this.composition}</span>`;
    }
    return this.candidates.slice(0, 7).map((word, idx) => `
      <div class="candidate-item ${idx === 0 ? 'highlight' : ''}" data-candidate="${word}">
        <span class="cand-num">${idx + 1}.</span>
        <span class="cand-word">${word}</span>
      </div>
    `).join('');
  }

  renderExpandedCandidateItems() {
    if (this.candidates.length === 0) return '';
    return this.candidates.map((word, idx) => `
      <div class="expanded-cand-item" data-candidate="${word}">
        <span class="cand-num">${idx + 1}.</span>
        <span class="cand-word">${word}</span>
      </div>
    `).join('');
  }

  updateCandidateBar() {
    const compEl = this.container.querySelector('#ime-composition-preview');
    const listEl = this.container.querySelector('#ime-candidate-list');
    const candBarEl = this.container.querySelector('.ime-candidate-bar');
    const expandedGridEl = this.container.querySelector('#ime-expanded-grid');

    if (compEl) {
      compEl.innerHTML = `
        <span class="comp-text">${this.composition}</span>
        ${this.composition ? '<span class="comp-cursor"></span>' : ''}
      `;
    }
    if (listEl) {
      listEl.innerHTML = this.renderCandidateItems();
    }
    if (candBarEl) {
      if (this.composition) {
        candBarEl.classList.add('has-composition');
      } else {
        candBarEl.classList.remove('has-composition');
      }
    }
    if (expandedGridEl) {
      expandedGridEl.innerHTML = this.renderExpandedCandidateItems();
    }
  }

  render() {
    const gameMacros = ['收到！', '集合进攻！', '稳住能赢！', '请求集合！', '保护输出！'];

    this.container.innerHTML = `
      <div class="ime-keyboard-wrapper ${this.isFloating ? 'floating-game-hud' : ''}">
        
        <!-- 如果是游戏悬浮形态，渲染 HUD 顶部状态条与战术宏指令 -->
        ${this.isFloating ? `
          <div class="gaming-hud-header">
            <div class="gaming-hud-title">
              <span class="hud-dot"></span> 🎮 游戏悬浮形态 (${this.getModeName()})
              <button class="tool-btn" data-action="toggle-floating" style="margin-left: auto; color: #38bdf8; font-size: 11px;">⬇️ 贴底固定</button>
            </div>
            <div class="gaming-macro-scroll">
              ${gameMacros.map(m => `<button class="game-macro-pill" data-macro="${m}">${m}</button>`).join('')}
            </div>
          </div>
        ` : ''}

        <!-- 顶部工具快捷栏 -->
        <div class="ime-quick-toolbar">
          <div class="tool-group left">
            <button class="tool-btn" data-action="toggle-mode-menu" title="切换输入法键位">
              <span class="tool-icon">⌨️</span>
              <span class="tool-label">${this.getModeName()}</span>
            </button>
            <button class="tool-btn" data-action="open-handwriting" title="智能手写">
              <span class="tool-icon">✍️</span>
              <span class="tool-label">手写</span>
            </button>
            <button class="tool-btn" data-action="open-voice" title="语音识别">
              <span class="tool-icon">🎙️</span>
              <span class="tool-label">语音</span>
            </button>
            <button class="tool-btn" data-action="open-clipboard" title="剪贴板">
              <span class="tool-icon">📋</span>
              <span class="tool-label">短语</span>
            </button>
          </div>
          <div class="tool-group right">
            <button class="tool-btn" data-action="toggle-floating" title="切换游戏悬浮形态/贴底固定形态">
              <span class="tool-icon">${this.isFloating ? '⬇️' : '🎮'}</span>
            </button>
            <button class="tool-btn" data-action="open-symbols" title="符号大全">
              <span class="tool-icon">#+=</span>
            </button>
            <button class="tool-btn" data-action="open-emoji" title="Emoji 与贴纸">
              <span class="tool-icon">😊</span>
            </button>
            <button class="tool-btn" data-action="open-settings" title="设置中心">
              <span class="tool-icon">⚙️</span>
            </button>
          </div>
        </div>

        <!-- 候选词栏 -->
        <div class="ime-candidate-bar ${this.composition ? 'has-composition' : ''}">
          <div class="composition-preview" id="ime-composition-preview">
            <span class="comp-text">${this.composition}</span>
            ${this.composition ? '<span class="comp-cursor"></span>' : ''}
          </div>
          <div class="candidate-list" id="ime-candidate-list">
            ${this.renderCandidateItems()}
          </div>
          <button class="candidate-expand-btn" data-action="expand-candidates" title="展开更多候选词">
            ▼
          </button>
        </div>

        <!-- 键盘主体区 (渲染 5 大核心键位之一) -->
        <div class="ime-keypad-container" id="ime-keypad-container">
          ${this.renderKeypadBody()}
        </div>

        <!-- 候选词展开浮层 -->
        <div class="ime-candidate-expanded-panel" id="ime-candidate-expanded" style="display: none;">
          <div class="expanded-header">
            <span>候选字词</span>
            <button class="close-expanded-btn" data-action="close-expanded">▲</button>
          </div>
          <div class="expanded-grid" id="ime-expanded-grid">
            ${this.renderExpandedCandidateItems()}
          </div>
        </div>
      </div>
    `;

    this.attachKeyPopups();
  }

  renderKeypadBody() {
    switch (this.mode) {
      case 'pinyin26':
        return this.render26Keypad(true); // 26键中文
      case 'english26':
        return this.render26Keypad(false); // 26键英文
      case 'pinyin9':
        return this.render9ChineseKeypad(); // 九键中文
      case 'english9':
        return this.render9EnglishKeypad(); // 九键英文 (T9)
      case 'digits':
        return this.renderDigitsKeypad(); // 数字键盘
      default:
        return this.render26Keypad(true);
    }
  }

  // 1. 26 键键盘 (中/英通用，isChinese 控制默认状态)
  render26Keypad(isChinese = true) {
    const row1 = ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'];
    const row2 = ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'];
    const row3 = ['z', 'x', 'c', 'v', 'b', 'n', 'm'];

    const subHints = {
      'q': '1', 'w': '2', 'e': '3', 'r': '4', 't': '5', 'y': '6', 'u': '7', 'i': '8', 'o': '9', 'p': '0',
      'a': '@', 's': '#', 'd': '$', 'f': '%', 'g': '&', 'h': '-', 'j': '+', 'k': '(', 'l': ')',
      'z': '*', 'x': '"', 'c': "'", 'v': ':', 'b': ';', 'n': '!', 'm': '?'
    };

    const transformChar = (c) => {
      return (this.isShift || this.isCapsLock) ? c.toUpperCase() : c.toLowerCase();
    };

    return `
      <div class="ime-keys-layout layout-26">
        <div class="ime-key-row">
          ${row1.map(key => `
            <button class="ime-key key-char" data-key="${key}">
              <span class="sub-hint">${subHints[key] || ''}</span>
              <span class="main-char">${transformChar(key)}</span>
            </button>
          `).join('')}
        </div>

        <div class="ime-key-row row-2">
          ${row2.map(key => `
            <button class="ime-key key-char" data-key="${key}">
              <span class="sub-hint">${subHints[key] || ''}</span>
              <span class="main-char">${transformChar(key)}</span>
            </button>
          `).join('')}
        </div>

        <div class="ime-key-row row-3">
          <button class="ime-key key-func key-shift ${this.isCapsLock ? 'caps-locked' : (this.isShift ? 'active' : '')}" data-key="Shift">
            <span>${this.isCapsLock ? '⇪' : '⇧'}</span>
          </button>
          ${row3.map(key => `
            <button class="ime-key key-char" data-key="${key}">
              <span class="sub-hint">${subHints[key] || ''}</span>
              <span class="main-char">${transformChar(key)}</span>
            </button>
          `).join('')}
          <button class="ime-key key-func key-backspace" data-key="Backspace">
            <span>⌫</span>
          </button>
        </div>

        <div class="ime-key-row row-bottom">
          <button class="ime-key key-func key-mode" data-key="ToggleMode">
            <span>${isChinese ? '英' : '中'}</span>
          </button>
          <button class="ime-key key-func key-symbols" data-key="OpenSymbols">
            <span>123</span>
          </button>
          <button class="ime-key key-space" data-key="Space">
            <span class="space-title">${isChinese ? '空格 / 空白' : 'space'}</span>
          </button>
          <button class="ime-key key-func key-dot" data-key="Dot">
            <span>${isChinese ? '。' : '.'}</span>
          </button>
          <button class="ime-key key-func key-enter" data-key="Enter">
            <span>${isChinese ? '确认' : 'Go'}</span>
          </button>
        </div>
      </div>
    `;
  }

  // 2. 九键中文键盘 (9-Key Chinese Pinyin)
  render9ChineseKeypad() {
    const keys9 = [
      { num: '1', sub: '.,?!@', main: '1' },
      { num: '2', sub: 'ABC', main: '2' },
      { num: '3', sub: 'DEF', main: '3' },
      { num: '4', sub: 'GHI', main: '4' },
      { num: '5', sub: 'JKL', main: '5' },
      { num: '6', sub: 'MNO', main: '6' },
      { num: '7', sub: 'PQRS', main: '7' },
      { num: '8', sub: 'TUV', main: '8' },
      { num: '9', sub: 'WXYZ', main: '9' },
      { num: '*', sub: '重输', main: '*' },
      { num: '0', sub: '空格', main: '0' },
      { num: '#', sub: '符号', main: '#' }
    ];

    return `
      <div class="ime-keys-layout layout-9">
        <div class="keypad9-filter-col" id="keypad9-filters">
          ${this.render9FiltersHtml()}
        </div>

        <div class="keypad9-grid">
          ${keys9.map(k => `
            <button class="ime-key key-9" data-key="${k.num}">
              <span class="num-9">${k.main}</span>
              <span class="sub-9">${k.sub}</span>
            </button>
          `).join('')}
        </div>

        <div class="keypad9-side-col">
          <button class="ime-key key-func key-backspace" data-key="Backspace">
            <span>⌫</span>
          </button>
          <button class="ime-key key-func key-mode" data-key="Switch26">
            <span>26键</span>
          </button>
          <button class="ime-key key-func key-enter" data-key="Enter">
            <span>确定</span>
          </button>
        </div>
      </div>
    `;
  }

  // 3. 九键英文键盘 (9-Key English T9 Keypad)
  render9EnglishKeypad() {
    const keys9 = [
      { num: '1', sub: '.,?!@', main: '1' },
      { num: '2', sub: 'abc', main: '2' },
      { num: '3', sub: 'def', main: '3' },
      { num: '4', sub: 'ghi', main: '4' },
      { num: '5', sub: 'jkl', main: '5' },
      { num: '6', sub: 'mno', main: '6' },
      { num: '7', sub: 'pqrs', main: '7' },
      { num: '8', sub: 'tuv', main: '8' },
      { num: '9', sub: 'wxyz', main: '9' },
      { num: '*', sub: '重输', main: '*' },
      { num: '0', sub: 'space', main: '0' },
      { num: '#', sub: '#+=', main: '#' }
    ];

    return `
      <div class="ime-keys-layout layout-9 layout-9-english">
        <div class="keypad9-filter-col" id="keypad9-filters">
          <div class="filter-9-item active">T9智能</div>
          <div class="filter-9-item">abc</div>
          <div class="filter-9-item">ABC</div>
        </div>

        <div class="keypad9-grid">
          ${keys9.map(k => `
            <button class="ime-key key-9" data-key="${k.num}">
              <span class="num-9">${k.main}</span>
              <span class="sub-9">${k.sub}</span>
            </button>
          `).join('')}
        </div>

        <div class="keypad9-side-col">
          <button class="ime-key key-func key-backspace" data-key="Backspace">
            <span>⌫</span>
          </button>
          <button class="ime-key key-func key-mode" data-key="Switch26">
            <span>26键</span>
          </button>
          <button class="ime-key key-func key-enter" data-key="Enter">
            <span>Go</span>
          </button>
        </div>
      </div>
    `;
  }

  // 4. 数字专用键盘 (Numeric Keypad)
  renderDigitsKeypad() {
    const digitRows = [
      ['1', '2', '3'],
      ['4', '5', '6'],
      ['7', '8', '9'],
      ['.', '0', '00']
    ];

    return `
      <div class="ime-keys-layout layout-digits">
        <div class="digits-grid">
          ${digitRows.map(row => `
            <div class="ime-key-row">
              ${row.map(d => `
                <button class="ime-key key-digit" data-key="${d}">
                  <span class="main-char">${d}</span>
                </button>
              `).join('')}
            </div>
          `).join('')}
        </div>
        <div class="digits-side-col">
          <button class="ime-key key-func key-backspace" data-key="Backspace">
            <span>⌫</span>
          </button>
          <button class="ime-key key-func" data-key="Switch26">
            <span>拼音</span>
          </button>
          <button class="ime-key key-func key-enter digits-enter" data-key="Enter">
            <span>确认</span>
          </button>
        </div>
      </div>
    `;
  }

  render9FiltersHtml() {
    if (this.pinyin9Filters.length === 0) {
      return `
        <div class="filter-9-item active">全拼</div>
        <div class="filter-9-item">双拼</div>
        <div class="filter-9-item">首字母</div>
      `;
    }
    return this.pinyin9Filters.map((py, idx) => `
      <div class="filter-9-item ${idx === 0 ? 'active' : ''}" data-filter="${py}">
        ${py}
      </div>
    `).join('');
  }

  update9KeyFilters() {
    const el = this.container.querySelector('#keypad9-filters');
    if (el) {
      el.innerHTML = this.render9FiltersHtml();
    }
  }

  bindContainerEvents() {
    this.container.addEventListener('click', (e) => {
      // 候选词选择
      const candItem = e.target.closest('[data-candidate]');
      if (candItem) {
        const word = candItem.getAttribute('data-candidate');
        this.onCandidateSelect(word);
        const exp = this.container.querySelector('#ime-candidate-expanded');
        if (exp) exp.style.display = 'none';
        return;
      }

      // 宏战术快捷短语
      const macroBtn = e.target.closest('[data-macro]');
      if (macroBtn) {
        const macroText = macroBtn.getAttribute('data-macro');
        this.onCandidateSelect(macroText);
        return;
      }

      // 工具栏操作
      const actionBtn = e.target.closest('[data-action]');
      if (actionBtn) {
        const action = actionBtn.getAttribute('data-action');
        if (action === 'expand-candidates') {
          const exp = this.container.querySelector('#ime-candidate-expanded');
          if (exp) exp.style.display = exp.style.display === 'none' ? 'flex' : 'none';
        } else if (action === 'close-expanded') {
          const exp = this.container.querySelector('#ime-candidate-expanded');
          if (exp) exp.style.display = 'none';
        } else if (action === 'toggle-floating') {
          this.toggleFloating();
        } else if (action === 'toggle-mode-menu') {
          // 在 5 大核心键位中循环切换
          const modes = ['pinyin26', 'english26', 'pinyin9', 'english9', 'digits'];
          const nextIdx = (modes.indexOf(this.mode) + 1) % modes.length;
          this.setMode(modes[nextIdx]);
        } else {
          this.onAction(action);
        }
        return;
      }

      // 9键拼音过滤器
      const filterItem = e.target.closest('[data-filter]');
      if (filterItem) {
        const py = filterItem.getAttribute('data-filter');
        this.container.querySelectorAll('.filter-9-item').forEach(f => f.classList.remove('active'));
        filterItem.classList.add('active');
        this.onAction('filter9-change', py);
        return;
      }

      // 按键点击
      const keyBtn = e.target.closest('.ime-key');
      if (keyBtn) {
        const key = keyBtn.getAttribute('data-key');
        this.handleKeyClick(key, keyBtn);
      }
    });
  }

  attachKeyPopups() {
    this.container.querySelectorAll('.key-char').forEach(btn => {
      const showPopup = () => {
        const char = btn.querySelector('.main-char')?.innerText || '';
        let popup = btn.querySelector('.key-popup-bubble');
        if (!popup) {
          popup = document.createElement('div');
          popup.className = 'key-popup-bubble';
          btn.appendChild(popup);
        }
        popup.innerText = char;
        popup.style.display = 'flex';
      };

      const hidePopup = () => {
        const popup = btn.querySelector('.key-popup-bubble');
        if (popup) popup.style.display = 'none';
      };

      btn.addEventListener('mousedown', showPopup);
      btn.addEventListener('mouseup', hidePopup);
      btn.addEventListener('mouseleave', hidePopup);
      btn.addEventListener('touchstart', showPopup, { passive: true });
      btn.addEventListener('touchend', hidePopup, { passive: true });
    });
  }

  handleKeyClick(key, element) {
    if (!key) return;

    if (key === 'Shift') {
      if (this.isShift) {
        this.isCapsLock = !this.isCapsLock;
        this.isShift = false;
      } else {
        this.isShift = true;
      }
      this.render();
      return;
    }

    if (key === 'ToggleMode') {
      if (this.mode === 'english26') {
        this.setMode('pinyin26');
      } else if (this.mode === 'pinyin26') {
        this.setMode('english26');
      } else if (this.mode === 'pinyin9') {
        this.setMode('english9');
      } else if (this.mode === 'english9') {
        this.setMode('pinyin9');
      }
      return;
    }

    if (key === 'Switch26') {
      this.setMode(this.mode === 'english9' ? 'english26' : 'pinyin26');
      return;
    }

    if (key === 'OpenSymbols') {
      this.onAction('open-symbols');
      return;
    }

    this.onKeyPress(key, element);

    if (this.isShift && !this.isCapsLock && key.length === 1) {
      this.isShift = false;
      this.render();
    }
  }
}
