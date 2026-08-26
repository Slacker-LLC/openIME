/**
 * IME App - 输入法全套 UI 总控与交互控制器
 */

import { IMEEngine, IME_DATA } from './ime-engine.js';
import { KeyboardView } from './keyboard-view.js';
import { HandwritingEngine } from './handwriting.js';
import { VoiceVisualizer, VoiceSpeechSimulator } from './voice-mock.js';
import { SoundFX } from './sound-fx.js';

class IMEApp {
  constructor() {
    this.engine = new IMEEngine();
    this.soundFx = new SoundFX();
    this.currentView = 'pinyin26'; // 'pinyin26' | 'pinyin9' | 'english' | 'symbols' | 'emoji' | 'handwriting' | 'voice' | 'clipboard' | 'settings' | 'desktop'
    this.activeInputTarget = null; // DOM input element currently receiving text

    this.composition = '';
    this.candidates = [];
    this.pinyin9Filters = [];

    // 桌面端状态
    this.desktopMode = 'zh'; // 'zh' | 'en'
    this.desktopLayout = 'horizontal'; // 'horizontal' | 'vertical'
    this.desktopPunctuation = 'zh'; // 'zh' | 'en'
    this.desktopShape = 'half'; // 'half' | 'full'
    this.desktopTrad = false;

    // 面板引擎实例缓存
    this.handwritingEngine = null;
    this.voiceVisualizer = null;
    this.voiceSimulator = null;

    this.init();
  }

  init() {
    this.initElements();
    this.initKeyboardView();
    this.initDesktopIME();
    this.initPhysicalKeyboard();
    this.initSandboxInputs();
    this.initThemeSwitcher();
    this.initInterfaceCards();
  }

  initElements() {
    this.dockContainer = document.getElementById('phone-ime-dock');
    this.phoneInput = document.getElementById('phone-mock-input');
    this.activeInputTarget = this.phoneInput;

    this.desktopFloatingBar = document.getElementById('desktop-floating-bar');
    this.desktopCandidateBox = document.getElementById('desktop-candidate-box');

    // 顶部全局音效切换按钮
    const headerSoundBtn = document.getElementById('header-sound-btn');
    const soundStatusText = document.getElementById('sound-status-text');
    if (headerSoundBtn) {
      headerSoundBtn.addEventListener('click', () => {
        this.soundFx.enabled = !this.soundFx.enabled;
        if (soundStatusText) {
          soundStatusText.innerText = this.soundFx.enabled ? '机械音效 开' : '音效已关';
        }
        headerSoundBtn.classList.toggle('active', this.soundFx.enabled);
        if (this.soundFx.enabled) {
          this.soundFx.playKeySound('char');
        }
      });
    }
  }

  initKeyboardView() {
    this.keyboardView = new KeyboardView(this.dockContainer, {
      onKeyPress: (key, el) => this.handleKeyPress(key, el),
      onCandidateSelect: (word) => this.commitCandidate(word),
      onAction: (action, data) => this.handleToolbarAction(action, data)
    });
  }

  // 处理按键输入
  handleKeyPress(key, el) {
    this.soundFx.playKeySound(key === 'Space' ? 'space' : (key === 'Enter' ? 'enter' : (key === 'Backspace' ? 'backspace' : 'char')));

    if (key === 'Backspace') {
      if (this.composition.length > 0) {
        this.composition = this.composition.slice(0, -1);
        this.updateIMEState();
      } else {
        this.deleteLastCharFromTarget();
      }
      return;
    }

    if (key === 'Space') {
      if (this.composition.length > 0 && this.candidates.length > 0) {
        this.commitCandidate(this.candidates[0]);
      } else {
        this.insertTextToTarget(' ');
      }
      return;
    }

    if (key === 'Enter') {
      if (this.composition.length > 0) {
        // 直接上屏原始英文字符串
        this.commitCandidate(this.composition);
      } else {
        this.insertTextToTarget('\n');
      }
      return;
    }

    if (key === 'Dot') {
      const dotChar = this.keyboardView.mode === 'english' ? '.' : '。';
      this.insertTextToTarget(dotChar);
      return;
    }

    // 笔画输入
    if (this.keyboardView.mode === 'stroke') {
      if (/^[1-5*]$/.test(key)) {
        this.composition += key;
        this.candidates = this.engine.getStrokeCandidates(this.composition);
        this.keyboardView.setComposition(this.composition, this.candidates);
        this.updateDesktopCandidates();
        return;
      }
    }

    // 九键中文输入
    if (this.keyboardView.mode === 'pinyin9') {
      if (/^[0-9]$/.test(key)) {
        this.composition += key;
        const res = this.engine.get9KeyCandidates(this.composition);
        this.candidates = res.candidates;
        this.pinyin9Filters = res.pinyins;
        this.keyboardView.setComposition(this.composition, this.candidates, this.pinyin9Filters);
        this.updateDesktopCandidates();
        return;
      }
    }

    // 九键英文 T9 智能输入
    if (this.keyboardView.mode === 'english9') {
      if (/^[0-9]$/.test(key)) {
        this.composition += key;
        this.candidates = this.engine.getT9EnglishCandidates(this.composition);
        this.keyboardView.setComposition(this.composition, this.candidates);
        this.updateDesktopCandidates();
        return;
      }
    }

    // 26 键字母输入 (26中 / 26英)
    if (/^[a-zA-Z]$/.test(key)) {
      if (this.keyboardView.mode === 'english26' || this.keyboardView.mode === 'english') {
        this.composition += key;
        this.candidates = this.engine.getEnglishCompletions(this.composition);
        this.keyboardView.setComposition(this.composition, this.candidates);
        this.updateDesktopCandidates();
      } else {
        this.composition += key.toLowerCase();
        this.updateIMEState();
      }
      return;
    }

    // 数字与普通字符直接上屏
    this.insertTextToTarget(key);
  }

  updateIMEState() {
    if (this.composition.length > 0) {
      if (this.keyboardView.mode === 'english9') {
        this.candidates = this.engine.getT9EnglishCandidates(this.composition);
      } else if (this.keyboardView.mode === 'stroke') {
        this.candidates = this.engine.getStrokeCandidates(this.composition);
      } else {
        this.candidates = this.engine.getCandidates(this.composition);
      }
    } else {
      this.candidates = [];
    }
    this.keyboardView.setComposition(this.composition, this.candidates);
    this.updateDesktopCandidates();
  }

  commitCandidate(word) {
    if (!word) return;
    this.insertTextToTarget(word);
    this.composition = '';
    this.candidates = [];
    this.keyboardView.setComposition('', []);
    this.updateDesktopCandidates();
    this.soundFx.playKeySound('space');
  }

  insertTextToTarget(text) {
    if (!this.activeInputTarget) return;

    if (this.activeInputTarget.tagName === 'TEXTAREA' || this.activeInputTarget.tagName === 'INPUT') {
      const start = this.activeInputTarget.selectionStart || 0;
      const end = this.activeInputTarget.selectionEnd || 0;
      const val = this.activeInputTarget.value;
      this.activeInputTarget.value = val.substring(0, start) + text + val.substring(end);
      this.activeInputTarget.selectionStart = this.activeInputTarget.selectionEnd = start + text.length;
      this.activeInputTarget.focus();
    } else {
      // Contenteditable or mock div
      this.activeInputTarget.innerText += text;
    }
  }

  deleteLastCharFromTarget() {
    if (!this.activeInputTarget) return;

    if (this.activeInputTarget.tagName === 'TEXTAREA' || this.activeInputTarget.tagName === 'INPUT') {
      const start = this.activeInputTarget.selectionStart || 0;
      const val = this.activeInputTarget.value;
      if (start > 0) {
        this.activeInputTarget.value = val.substring(0, start - 1) + val.substring(start);
        this.activeInputTarget.selectionStart = this.activeInputTarget.selectionEnd = start - 1;
      }
    } else {
      const txt = this.activeInputTarget.innerText || '';
      if (txt.length > 0) {
        this.activeInputTarget.innerText = txt.slice(0, -1);
      }
    }
  }

  // 工具栏与功能动作处理
  handleToolbarAction(action, data) {
    switch (action) {
      case 'toggle-mode-menu':
        const nextMode = this.keyboardView.mode === 'pinyin26' ? 'pinyin9' : (this.keyboardView.mode === 'pinyin9' ? 'english' : 'pinyin26');
        this.switchInterface(nextMode);
        break;
      case 'open-symbols':
        this.switchInterface('symbols');
        break;
      case 'open-emoji':
        this.switchInterface('emoji');
        break;
      case 'open-handwriting':
        this.switchInterface('handwriting');
        break;
      case 'open-voice':
        this.switchInterface('voice');
        break;
      case 'open-clipboard':
        this.switchInterface('clipboard');
        break;
      case 'open-settings':
        this.switchInterface('settings');
        break;
      case 'filter9-change':
        if (data) {
          this.candidates = this.engine.getCandidates(data);
          this.keyboardView.setComposition(this.composition, this.candidates, this.pinyin9Filters);
        }
        break;
    }
  }

  // 界面主切换调度器
  switchInterface(viewName) {
    this.currentView = viewName;
    this.updateActiveCard(viewName);

    if (viewName === 'pinyin26') {
      this.keyboardView.setMode('pinyin26');
      this.dockContainer.style.display = 'block';
    } else if (viewName === 'english26' || viewName === 'english') {
      this.keyboardView.setMode('english26');
      this.dockContainer.style.display = 'block';
    } else if (viewName === 'pinyin9') {
      this.keyboardView.setMode('pinyin9');
      this.dockContainer.style.display = 'block';
    } else if (viewName === 'english9') {
      this.keyboardView.setMode('english9');
      this.dockContainer.style.display = 'block';
    } else if (viewName === 'digits') {
      this.keyboardView.setMode('digits');
      this.dockContainer.style.display = 'block';
    } else if (viewName === 'stroke') {
      this.keyboardView.setMode('stroke');
      this.dockContainer.style.display = 'block';
    } else if (viewName === 'gaming') {
      this.keyboardView.setMode('gaming');
      this.dockContainer.style.display = 'block';
    } else if (viewName === 'symbols') {
      this.renderSymbolsPanel();
    } else if (viewName === 'emoji') {
      this.renderEmojiPanel();
    } else if (viewName === 'handwriting') {
      this.renderHandwritingPanel();
    } else if (viewName === 'voice') {
      this.renderVoicePanel();
    } else if (viewName === 'clipboard') {
      this.renderClipboardPanel();
    } else if (viewName === 'aiwriter') {
      this.renderAIAssistantPanel();
    } else if (viewName === 'texteditor') {
      this.renderTextEditorPanel();
    } else if (viewName === 'skindiy') {
      this.renderSkinDIYPanel();
    } else if (viewName === 'settings') {
      this.renderSettingsPanel();
    } else if (viewName === 'desktop') {
      // 桌面悬浮状态演示
      this.dockContainer.style.display = 'block';
      this.keyboardView.setMode('pinyin26');
      this.highlightDesktopWidgets();
    }
  }

  updateActiveCard(viewName) {
    document.querySelectorAll('.interface-card').forEach(card => {
      if (card.getAttribute('data-view') === viewName) {
        card.classList.add('active');
      } else {
        card.classList.remove('active');
      }
    });
  }

  // AI 智能帮写面板 (朋友圈/小红书/高情商回复/周报)
  renderAIAssistantPanel() {
    const categories = Object.keys(IME_DATA.aiWriterPrompts);
    let activeCat = categories[0];

    const renderPrompts = (cat) => {
      const list = IME_DATA.aiWriterPrompts[cat] || [];
      return `
        <div class="ai-prompts-list">
          ${list.map(p => `
            <div class="ai-prompt-card" data-ai-text="${p}">
              <div class="ai-card-badge">AI 推荐</div>
              <div class="ai-card-content">${p}</div>
            </div>
          `).join('')}
        </div>
      `;
    };

    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel ai-writer-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>✨</span> AI 智能帮写与高情商助理
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="ai-writer-layout">
          <div class="ai-tabs-bar" id="ai-tabs">
            ${categories.map(c => `
              <button class="ai-tab-btn ${c === activeCat ? 'active' : ''}" data-ai-cat="${c}">${c}</button>
            `).join('')}
          </div>
          <div id="ai-prompts-wrapper" style="flex: 1; overflow-y: auto; padding: 8px;">
            ${renderPrompts(activeCat)}
          </div>
        </div>
      </div>
    `;

    const tabsBar = this.dockContainer.querySelector('#ai-tabs');
    const promptsWrapper = this.dockContainer.querySelector('#ai-prompts-wrapper');
    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');

    tabsBar.addEventListener('click', (e) => {
      const tab = e.target.closest('.ai-tab-btn');
      if (tab) {
        activeCat = tab.getAttribute('data-ai-cat');
        tabsBar.querySelectorAll('.ai-tab-btn').forEach(b => b.classList.remove('active'));
        tab.classList.add('active');
        promptsWrapper.innerHTML = renderPrompts(activeCat);
      }
    });

    this.dockContainer.addEventListener('click', (e) => {
      const card = e.target.closest('[data-ai-text]');
      if (card) {
        this.insertTextToTarget(card.getAttribute('data-ai-text'));
        this.soundFx.playKeySound('char');
      }
    });

    closeBtn.addEventListener('click', () => this.switchInterface('pinyin26'));
  }

  // 文本编辑与十字光标触控板面板
  renderTextEditorPanel() {
    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel text-editor-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>🎯</span> 文本编辑与光标十字精准定位
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="text-editor-layout">
          <!-- 顶部快捷操作栏 -->
          <div class="editor-quick-actions">
            <button class="editor-action-btn" data-edit-action="select-all">全选</button>
            <button class="editor-action-btn" data-edit-action="copy">复制</button>
            <button class="editor-action-btn" data-edit-action="cut">剪切</button>
            <button class="editor-action-btn" data-edit-action="paste">粘贴</button>
            <button class="editor-action-btn" data-edit-action="undo">撤销</button>
          </div>
          <!-- 光标方向十字盘 -->
          <div class="cursor-cross-pad">
            <div class="cross-row">
              <button class="cross-btn" data-cursor="up">▲</button>
            </div>
            <div class="cross-row middle">
              <button class="cross-btn" data-cursor="left">◀</button>
              <div class="cross-center-label">光标</div>
              <button class="cross-btn" data-cursor="right">▶</button>
            </div>
            <div class="cross-row">
              <button class="cross-btn" data-cursor="down">▼</button>
            </div>
          </div>
        </div>
      </div>
    `;

    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');
    closeBtn.addEventListener('click', () => this.switchInterface('pinyin26'));

    this.dockContainer.addEventListener('click', (e) => {
      const crossBtn = e.target.closest('[data-cursor]');
      if (crossBtn && this.activeInputTarget) {
        const dir = crossBtn.getAttribute('data-cursor');
        this.soundFx.playKeySound('char');
        if (dir === 'left' && this.activeInputTarget.selectionStart > 0) {
          this.activeInputTarget.selectionStart = this.activeInputTarget.selectionEnd = this.activeInputTarget.selectionStart - 1;
        } else if (dir === 'right') {
          this.activeInputTarget.selectionStart = this.activeInputTarget.selectionEnd = (this.activeInputTarget.selectionStart || 0) + 1;
        }
      }

      const actionBtn = e.target.closest('[data-edit-action]');
      if (actionBtn && this.activeInputTarget) {
        const act = actionBtn.getAttribute('data-edit-action');
        if (act === 'select-all') {
          this.activeInputTarget.select?.();
        } else if (act === 'copy') {
          navigator.clipboard.writeText(this.activeInputTarget.value || this.activeInputTarget.innerText || '');
        } else if (act === 'paste') {
          this.insertTextToTarget('【已粘贴剪贴板内容】');
        }
      }
    });
  }

  // 皮肤与外观 DIY 定制工坊面板
  renderSkinDIYPanel() {
    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel skin-diy-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>🎨</span> 键盘皮肤 DIY 定制工坊
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="skin-diy-layout">
          <div class="diy-slider-group">
            <div class="diy-slider-row">
              <span>按键透明度</span>
              <input type="range" min="30" max="100" value="95" class="diy-slider" id="diy-opacity" />
            </div>
            <div class="diy-slider-row">
              <span>按键圆角半径</span>
              <input type="range" min="2" max="20" value="8" class="diy-slider" id="diy-radius" />
            </div>
            <div class="diy-slider-row">
              <span>按键字体大小</span>
              <input type="range" min="14" max="22" value="17" class="diy-slider" id="diy-fontsize" />
            </div>
          </div>
          <div class="diy-preset-colors">
            <span style="font-size: 11px; color: #94a3b8;">主色调微调：</span>
            <div class="diy-palette">
              <span class="palette-dot" style="background: #2563eb;" data-diy-color="#2563eb"></span>
              <span class="palette-dot" style="background: #8b5cf6;" data-diy-color="#8b5cf6"></span>
              <span class="palette-dot" style="background: #ec4899;" data-diy-color="#ec4899"></span>
              <span class="palette-dot" style="background: #10b981;" data-diy-color="#10b981"></span>
              <span class="palette-dot" style="background: #f59e0b;" data-diy-color="#f59e0b"></span>
              <span class="palette-dot" style="background: #06b6d4;" data-diy-color="#06b6d4"></span>
            </div>
          </div>
        </div>
      </div>
    `;

    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');
    closeBtn.addEventListener('click', () => this.switchInterface('pinyin26'));

    const opacitySlider = this.dockContainer.querySelector('#diy-opacity');
    const radiusSlider = this.dockContainer.querySelector('#diy-radius');
    const fontSlider = this.dockContainer.querySelector('#diy-fontsize');

    opacitySlider?.addEventListener('input', (e) => {
      document.documentElement.style.setProperty('--ime-key-bg-opacity', e.target.value / 100);
    });

    radiusSlider?.addEventListener('input', (e) => {
      document.documentElement.style.setProperty('--ime-key-radius', `${e.target.value}px`);
    });

    fontSlider?.addEventListener('input', (e) => {
      document.documentElement.style.setProperty('--ime-font-size', `${e.target.value}px`);
    });

    this.dockContainer.querySelectorAll('.palette-dot').forEach(dot => {
      dot.addEventListener('click', () => {
        const col = dot.getAttribute('data-diy-color');
        document.documentElement.style.setProperty('--primary-color', col);
      });
    });
  }

  // 1. 渲染符号大全面板
  renderSymbolsPanel() {
    const categories = Object.keys(IME_DATA.symbols);
    let activeCat = categories[0];

    const renderGrid = (cat) => {
      const items = IME_DATA.symbols[cat] || [];
      const isKaomoji = cat === '网络颜文字';
      return `
        <div class="symbols-grid-container ${isKaomoji ? 'kaomoji-grid' : ''}">
          ${items.map(sym => `
            <div class="${isKaomoji ? 'kaomoji-cell' : 'symbol-cell'}" data-symbol="${sym}">
              ${sym}
            </div>
          `).join('')}
        </div>
      `;
    };

    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel symbols-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>#+=</span> 符号大全
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="symbols-panel-layout">
          <div class="symbols-cat-sidebar" id="symbols-sidebar">
            ${categories.map(cat => `
              <div class="symbols-cat-item ${cat === activeCat ? 'active' : ''}" data-cat="${cat}">
                ${cat}
              </div>
            `).join('')}
          </div>
          <div id="symbols-grid-wrapper" style="flex: 1; display: flex; overflow: hidden;">
            ${renderGrid(activeCat)}
          </div>
        </div>
      </div>
    `;

    // 绑定分类点击与符号点击
    const sidebar = this.dockContainer.querySelector('#symbols-sidebar');
    const gridWrapper = this.dockContainer.querySelector('#symbols-grid-wrapper');
    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');

    sidebar.addEventListener('click', (e) => {
      const item = e.target.closest('[data-cat]');
      if (item) {
        activeCat = item.getAttribute('data-cat');
        sidebar.querySelectorAll('.symbols-cat-item').forEach(i => i.classList.remove('active'));
        item.classList.add('active');
        gridWrapper.innerHTML = renderGrid(activeCat);
      }
    });

    this.dockContainer.addEventListener('click', (e) => {
      const cell = e.target.closest('[data-symbol]');
      if (cell) {
        const sym = cell.getAttribute('data-symbol');
        this.insertTextToTarget(sym);
        this.soundFx.playKeySound('char');
      }
    });

    closeBtn.addEventListener('click', () => {
      this.switchInterface('pinyin26');
    });
  }

  // 2. 渲染 Emoji 与表情包贴纸面板
  renderEmojiPanel() {
    const categories = Object.keys(IME_DATA.emojis);
    let activeCat = categories[0];
    let showStickers = false;

    const renderEmojiContent = () => {
      if (showStickers) {
        return `
          <div class="stickers-grid">
            ${IME_DATA.stickers.map(s => `
              <div class="sticker-card" data-sticker-text="${s.text}">
                <img src="${s.url}" alt="${s.title}" />
                <span class="sticker-title">${s.title}</span>
              </div>
            `).join('')}
          </div>
        `;
      }
      const list = IME_DATA.emojis[activeCat] || [];
      return `
        <div class="emoji-grid-content">
          ${list.map(e => `<div class="emoji-item" data-emoji="${e}">${e}</div>`).join('')}
        </div>
      `;
    };

    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel emoji-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>😊</span> Emoji & 贴纸表情
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="emoji-panel-layout">
          <div class="emoji-tabs-bar" id="emoji-tabs">
            ${categories.map(c => `
              <button class="emoji-tab-btn ${c === activeCat && !showStickers ? 'active' : ''}" data-cat="${c}">${c}</button>
            `).join('')}
            <button class="emoji-tab-btn ${showStickers ? 'active' : ''}" data-sticker-tab="true">🔥 贴纸/动图</button>
          </div>
          <div id="emoji-content-box" style="flex: 1; overflow: hidden; display: flex;">
            ${renderEmojiContent()}
          </div>
        </div>
      </div>
    `;

    const tabsBar = this.dockContainer.querySelector('#emoji-tabs');
    const contentBox = this.dockContainer.querySelector('#emoji-content-box');
    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');

    tabsBar.addEventListener('click', (e) => {
      const tab = e.target.closest('.emoji-tab-btn');
      if (tab) {
        if (tab.hasAttribute('data-sticker-tab')) {
          showStickers = true;
        } else {
          showStickers = false;
          activeCat = tab.getAttribute('data-cat');
        }
        tabsBar.querySelectorAll('.emoji-tab-btn').forEach(b => b.classList.remove('active'));
        tab.classList.add('active');
        contentBox.innerHTML = renderEmojiContent();
      }
    });

    this.dockContainer.addEventListener('click', (e) => {
      const emojiEl = e.target.closest('[data-emoji]');
      if (emojiEl) {
        this.insertTextToTarget(emojiEl.getAttribute('data-emoji'));
        this.soundFx.playKeySound('char');
        return;
      }
      const stickerEl = e.target.closest('[data-sticker-text]');
      if (stickerEl) {
        this.insertTextToTarget(stickerEl.getAttribute('data-sticker-text'));
        this.soundFx.playKeySound('char');
      }
    });

    closeBtn.addEventListener('click', () => {
      this.switchInterface('pinyin26');
    });
  }

  // 3. 渲染 Canvas 手写板
  renderHandwritingPanel() {
    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel handwriting-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>✍️</span> 智能手写板 (支持连笔与多字)
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="handwriting-panel-layout">
          <!-- 识别候选栏 -->
          <div class="handwriting-top-candidates" id="hw-candidates">
            <span style="font-size: 12px; color: #94a3b8;">在下方区域落笔手写...</span>
          </div>
          <!-- 绘图画板 -->
          <div class="handwriting-canvas-box">
            <canvas id="handwriting-canvas"></canvas>
          </div>
          <!-- 底部控制栏 -->
          <div class="handwriting-action-bar">
            <div class="hw-actions-left">
              <button class="hw-btn" id="btn-hw-undo">撤销笔画</button>
              <button class="hw-btn" id="btn-hw-clear">重写清空</button>
            </div>
            <button class="hw-btn primary" id="btn-hw-space">空格</button>
          </div>
        </div>
      </div>
    `;

    const canvas = this.dockContainer.querySelector('#handwriting-canvas');
    const candEl = this.dockContainer.querySelector('#hw-candidates');
    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');
    const undoBtn = this.dockContainer.querySelector('#btn-hw-undo');
    const clearBtn = this.dockContainer.querySelector('#btn-hw-clear');
    const spaceBtn = this.dockContainer.querySelector('#btn-hw-space');

    this.handwritingEngine = new HandwritingEngine(
      canvas,
      (candidates) => {
        if (candidates.length === 0) {
          candEl.innerHTML = `<span style="font-size: 12px; color: #94a3b8;">在下方区域落笔手写...</span>`;
        } else {
          candEl.innerHTML = candidates.map(w => `<div class="hw-cand-item" data-hw-word="${w}">${w}</div>`).join('');
        }
      },
      () => {
        this.soundFx.playKeySound('char');
      }
    );

    candEl.addEventListener('click', (e) => {
      const item = e.target.closest('[data-hw-word]');
      if (item) {
        const word = item.getAttribute('data-hw-word');
        this.commitCandidate(word);
        this.handwritingEngine.clear();
      }
    });

    undoBtn.addEventListener('click', () => this.handwritingEngine.undo());
    clearBtn.addEventListener('click', () => this.handwritingEngine.clear());
    spaceBtn.addEventListener('click', () => this.insertTextToTarget(' '));
    closeBtn.addEventListener('click', () => this.switchInterface('pinyin26'));
  }

  // 4. 渲染语音输入面板
  renderVoicePanel() {
    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel voice-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>🎙️</span> AI 动态声波语音输入
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="voice-panel-layout">
          <!-- 识别文本流卡片 -->
          <div class="voice-status-box" id="voice-transcript-box">
            <span class="voice-transcript-placeholder">按住下方麦克风开始说话，实时转文字...</span>
          </div>
          <!-- 动态波形画布 -->
          <canvas class="voice-waveform-canvas" id="voice-wave-canvas"></canvas>
          <!-- 语音控制栏 -->
          <div class="voice-controls-bar">
            <select class="voice-lang-select" id="voice-lang-picker">
              <option value="mandarin">普通话 (中文)</option>
              <option value="cantonese">粤语 (广东话)</option>
              <option value="english">English (英语)</option>
              <option value="sichuan">四川方言</option>
            </select>
            <button class="voice-mic-main-btn" id="btn-voice-mic" title="点击开始/停止录音">
              🎤
            </button>
            <button class="hw-btn primary" id="btn-voice-commit">上屏</button>
          </div>
        </div>
      </div>
    `;

    const canvas = this.dockContainer.querySelector('#voice-wave-canvas');
    const micBtn = this.dockContainer.querySelector('#btn-voice-mic');
    const transcriptBox = this.dockContainer.querySelector('#voice-transcript-box');
    const langPicker = this.dockContainer.querySelector('#voice-lang-picker');
    const commitBtn = this.dockContainer.querySelector('#btn-voice-commit');
    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');

    this.voiceVisualizer = new VoiceVisualizer(canvas);
    let currentSpeechText = '';

    this.voiceSimulator = new VoiceSpeechSimulator(
      (chunk) => {
        currentSpeechText = chunk;
        transcriptBox.innerHTML = `<span class="voice-transcript-text">${chunk}</span>`;
      },
      (finalText) => {
        currentSpeechText = finalText;
        this.voiceVisualizer.stop();
        micBtn.classList.remove('recording');
        micBtn.innerHTML = '🎤';
      }
    );

    micBtn.addEventListener('click', () => {
      if (this.voiceVisualizer.isRecording) {
        this.voiceVisualizer.stop();
        this.voiceSimulator.stop();
        micBtn.classList.remove('recording');
        micBtn.innerHTML = '🎤';
      } else {
        this.voiceVisualizer.start();
        micBtn.classList.add('recording');
        micBtn.innerHTML = '⏹️';
        this.voiceSimulator.start(langPicker.value);
      }
    });

    commitBtn.addEventListener('click', () => {
      if (currentSpeechText) {
        this.insertTextToTarget(currentSpeechText);
        currentSpeechText = '';
        transcriptBox.innerHTML = `<span class="voice-transcript-placeholder">识别完成并已上屏。可继续点击录音...</span>`;
      }
    });

    closeBtn.addEventListener('click', () => {
      if (this.voiceVisualizer) this.voiceVisualizer.stop();
      if (this.voiceSimulator) this.voiceSimulator.stop();
      this.switchInterface('pinyin26');
    });
  }

  // 5. 渲染剪贴板与快捷短语面板
  renderClipboardPanel() {
    let activeTab = 'history'; // 'history' | 'phrases'

    const renderContent = () => {
      if (activeTab === 'history') {
        return `
          <div class="clipboard-list">
            ${IME_DATA.clipboardHistory.map(item => `
              <div class="clipboard-card" data-clip-text="${item.text}">
                <div class="clipboard-text">${item.text}</div>
                <div class="clipboard-meta">
                  ${item.pinned ? '<span class="clip-pin-icon">📌</span>' : ''}
                  <span>${item.time}</span>
                </div>
              </div>
            `).join('')}
          </div>
        `;
      }
      // 常用短语
      return `
        <div class="clipboard-list">
          ${Object.entries(IME_DATA.quickPhrases).map(([cat, phrases]) => `
            <div style="font-size: 11px; font-weight: 700; color: #94a3b8; padding: 4px 6px;">${cat}</div>
            ${phrases.map(p => `
              <div class="clipboard-card" data-clip-text="${p}">
                <div class="clipboard-text">${p}</div>
              </div>
            `).join('')}
          `).join('')}
        </div>
      `;
    };

    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel clipboard-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>📋</span> 剪贴板与快捷短语
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="clipboard-panel-layout">
          <div class="clipboard-nav-tabs" id="clip-tabs">
            <button class="clip-tab-btn active" data-tab="history">历史剪贴记录</button>
            <button class="clip-tab-btn" data-tab="phrases">懒人常用短语</button>
          </div>
          <div id="clip-content-box" style="flex: 1; overflow: hidden; display: flex; flex-direction: column;">
            ${renderContent()}
          </div>
        </div>
      </div>
    `;

    const tabsBar = this.dockContainer.querySelector('#clip-tabs');
    const contentBox = this.dockContainer.querySelector('#clip-content-box');
    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');

    tabsBar.addEventListener('click', (e) => {
      const tab = e.target.closest('.clip-tab-btn');
      if (tab) {
        activeTab = tab.getAttribute('data-tab');
        tabsBar.querySelectorAll('.clip-tab-btn').forEach(b => b.classList.remove('active'));
        tab.classList.add('active');
        contentBox.innerHTML = renderContent();
      }
    });

    this.dockContainer.addEventListener('click', (e) => {
      const card = e.target.closest('[data-clip-text]');
      if (card) {
        this.insertTextToTarget(card.getAttribute('data-clip-text'));
        this.soundFx.playKeySound('char');
      }
    });

    closeBtn.addEventListener('click', () => {
      this.switchInterface('pinyin26');
    });
  }

  // 6. 渲染设置与主题面板
  renderSettingsPanel() {
    this.dockContainer.innerHTML = `
      <div class="ime-sub-panel settings-panel">
        <div class="panel-top-nav">
          <div class="panel-heading">
            <span>⚙️</span> 输入法设置与主题换肤
          </div>
          <button class="panel-close-btn" id="btn-close-subpanel">✕ 键盘</button>
        </div>
        <div class="settings-panel-layout">
          <!-- 声音与触感 -->
          <div class="settings-group">
            <div class="settings-group-title">声音与按键音效 (Web Audio API)</div>
            <div class="settings-row">
              <div>
                <div class="settings-label">按键音效开关</div>
                <div class="settings-desc">敲击键盘时生成仿真机械/打字音</div>
              </div>
              <label class="ios-switch">
                <input type="checkbox" id="setting-sound-toggle" ${this.soundFx.enabled ? 'checked' : ''} />
                <span class="slider"></span>
              </label>
            </div>
            <div class="settings-row">
              <div class="settings-label">音效风格配置</div>
              <select class="voice-lang-select" id="setting-sound-profile">
                <option value="mechanical">清脆机械青轴</option>
                <option value="soft">iOS 原生轻触音</option>
              </select>
            </div>
          </div>

          <!-- 输入辅助 -->
          <div class="settings-group">
            <div class="settings-group-title">输入法习惯偏好</div>
            <div class="settings-row">
              <div>
                <div class="settings-label">模糊音智能纠错 (z/zh, c/ch, s/sh)</div>
                <div class="settings-desc">智能识别平翘舌与前后鼻音混淆</div>
              </div>
              <label class="ios-switch">
                <input type="checkbox" checked />
                <span class="slider"></span>
              </label>
            </div>
            <div class="settings-row">
              <div>
                <div class="settings-label">长按弹出气泡预览 (Key Popup)</div>
                <div class="settings-desc">在手指触按位置显示放大键帽</div>
              </div>
              <label class="ios-switch">
                <input type="checkbox" checked />
                <span class="slider"></span>
              </label>
            </div>
          </div>
        </div>
      </div>
    `;

    const soundToggle = this.dockContainer.querySelector('#setting-sound-toggle');
    const soundProfile = this.dockContainer.querySelector('#setting-sound-profile');
    const closeBtn = this.dockContainer.querySelector('#btn-close-subpanel');

    soundToggle.addEventListener('change', (e) => {
      this.soundFx.enabled = e.target.checked;
    });

    soundProfile.addEventListener('change', (e) => {
      this.soundFx.currentProfile = e.target.value;
      this.soundFx.playKeySound('char');
    });

    closeBtn.addEventListener('click', () => {
      this.switchInterface('pinyin26');
    });
  }

  // 7. 桌面端悬浮条与候选框逻辑
  initDesktopIME() {
    // 悬浮状态栏拖拽
    let isDragging = false;
    let offsetX = 0;
    let offsetY = 0;

    const dragHandle = this.desktopFloatingBar.querySelector('.desktop-drag-handle');
    dragHandle.addEventListener('mousedown', (e) => {
      isDragging = true;
      const rect = this.desktopFloatingBar.getBoundingClientRect();
      offsetX = e.clientX - rect.left;
      offsetY = e.clientY - rect.top;
      document.body.style.userSelect = 'none';
    });

    window.addEventListener('mousemove', (e) => {
      if (!isDragging) return;
      this.desktopFloatingBar.style.position = 'fixed';
      this.desktopFloatingBar.style.left = `${e.clientX - offsetX}px`;
      this.desktopFloatingBar.style.top = `${e.clientY - offsetY}px`;
      this.desktopFloatingBar.style.bottom = 'auto';
      this.desktopFloatingBar.style.right = 'auto';
      this.desktopFloatingBar.style.zIndex = '9999';
    });

    window.addEventListener('mouseup', () => {
      if (isDragging) {
        isDragging = false;
        document.body.style.userSelect = '';
      }
    });

    // 中英文切换按钮
    const btnLang = document.getElementById('desktop-btn-lang');
    if (btnLang) {
      btnLang.addEventListener('click', () => {
        this.desktopMode = this.desktopMode === 'zh' ? 'en' : 'zh';
        btnLang.querySelector('.desktop-status-badge').innerText = this.desktopMode === 'zh' ? '中' : '英';
      });
    }

    // 标点切换
    const btnPunct = document.getElementById('desktop-btn-punct');
    if (btnPunct) {
      btnPunct.addEventListener('click', () => {
        this.desktopPunctuation = this.desktopPunctuation === 'zh' ? 'en' : 'zh';
        btnPunct.querySelector('.desktop-status-badge').innerText = this.desktopPunctuation === 'zh' ? '。，' : '.,';
      });
    }

    // 全/半角切换
    const btnShape = document.getElementById('desktop-btn-shape');
    if (btnShape) {
      btnShape.addEventListener('click', () => {
        this.desktopShape = this.desktopShape === 'half' ? 'full' : 'half';
        btnShape.querySelector('.desktop-status-badge').innerText = this.desktopShape === 'half' ? '半' : '全';
      });
    }

    // 横竖排候选词切换
    const btnLayout = document.getElementById('desktop-btn-layout');
    if (btnLayout) {
      btnLayout.addEventListener('click', () => {
        this.desktopLayout = this.desktopLayout === 'horizontal' ? 'vertical' : 'horizontal';
        btnLayout.querySelector('.desktop-status-badge').innerText = this.desktopLayout === 'horizontal' ? '横排' : '竖排';
        this.updateDesktopCandidates();
      });
    }
  }

  updateDesktopCandidates() {
    if (!this.desktopCandidateBox) return;

    const compEl = document.getElementById('desktop-comp-text');
    const listEl = document.getElementById('desktop-candidates-strip');

    if (compEl) {
      compEl.innerText = this.composition || (this.desktopMode === 'zh' ? '中文输入' : 'English');
    }

    if (listEl) {
      listEl.className = `desktop-candidates-strip ${this.desktopLayout}`;
      if (this.candidates.length === 0) {
        listEl.innerHTML = `
          <div class="desktop-candidate-item selected">
            <span class="desktop-cand-index">1.</span>
            <span class="desktop-cand-text">${this.composition || '等待输入...'}</span>
          </div>
        `;
      } else {
        listEl.innerHTML = this.candidates.slice(0, 5).map((w, idx) => `
          <div class="desktop-candidate-item ${idx === 0 ? 'selected' : ''}" data-desktop-cand="${w}">
            <span class="desktop-cand-index">${idx + 1}.</span>
            <span class="desktop-cand-text">${w}</span>
          </div>
        `).join('');
      }
    }

    // 绑定点击上屏
    listEl.querySelectorAll('[data-desktop-cand]').forEach(item => {
      item.addEventListener('click', () => {
        const word = item.getAttribute('data-desktop-cand');
        this.commitCandidate(word);
      });
    });
  }

  highlightDesktopWidgets() {
    this.desktopFloatingBar.style.transform = 'scale(1.04)';
    this.desktopCandidateBox.style.transform = 'scale(1.04)';
    setTimeout(() => {
      this.desktopFloatingBar.style.transform = '';
      this.desktopCandidateBox.style.transform = '';
    }, 400);
  }

  // 8. 物理键盘事件监听 (PC 物理键盘打字与软键盘完全同步体验)
  initPhysicalKeyboard() {
    window.addEventListener('keydown', (e) => {
      // 避免在普通外部表单干扰，但在输入法演示激活时优先响应
      if (e.target.tagName === 'INPUT' && e.target.type !== 'text') return;

      const key = e.key;

      // 字母键 a-z
      if (/^[a-zA-Z]$/.test(key)) {
        this.handleKeyPress(key);
        e.preventDefault();
        return;
      }

      // 数字键 1-9 用于快速选词
      if (/^[1-9]$/.test(key) && this.composition.length > 0 && this.candidates.length > 0) {
        const idx = parseInt(key) - 1;
        if (this.candidates[idx]) {
          this.commitCandidate(this.candidates[idx]);
          e.preventDefault();
          return;
        }
      }

      // 空格选第1候选词
      if (key === ' ') {
        if (this.composition.length > 0 && this.candidates.length > 0) {
          this.commitCandidate(this.candidates[0]);
          e.preventDefault();
          return;
        }
      }

      // 回退键
      if (key === 'Backspace') {
        if (this.composition.length > 0) {
          this.handleKeyPress('Backspace');
          e.preventDefault();
          return;
        }
      }

      // 回车键
      if (key === 'Enter') {
        if (this.composition.length > 0) {
          this.handleKeyPress('Enter');
          e.preventDefault();
          return;
        }
      }
    });
  }

  // 9. 沙盒输入框交互绑定
  initSandboxInputs() {
    const sendBtn = document.getElementById('phone-send-btn');
    const chatContainer = document.getElementById('phone-chat-stream');

    const handleSend = () => {
      const text = this.phoneInput.innerText.trim();
      if (text) {
        const bubble = document.createElement('div');
        bubble.className = 'chat-bubble sender';
        bubble.innerText = text;
        chatContainer.appendChild(bubble);
        this.phoneInput.innerText = '';
        chatContainer.scrollTop = chatContainer.scrollHeight;

        // 模拟自动回复
        setTimeout(() => {
          const replies = [
            '收到！输入法 UI 体验非常流畅细腻！',
            '手写板与 26/9 键切换响应极快，点赞！',
            '语音波形动画和桌面候选框联动效果很棒！'
          ];
          const rep = replies[Math.floor(Math.random() * replies.length)];
          const repBubble = document.createElement('div');
          repBubble.className = 'chat-bubble receiver';
          repBubble.innerText = rep;
          chatContainer.appendChild(repBubble);
          chatContainer.scrollTop = chatContainer.scrollHeight;
        }, 600);
      }
    };

    if (sendBtn) {
      sendBtn.addEventListener('click', handleSend);
    }

    if (this.phoneInput) {
      this.phoneInput.addEventListener('focus', () => {
        this.activeInputTarget = this.phoneInput;
      });
    }

    const sandboxTextarea = document.getElementById('sandbox-textarea');
    if (sandboxTextarea) {
      sandboxTextarea.addEventListener('focus', () => {
        this.activeInputTarget = sandboxTextarea;
      });
    }

    const btnClearSandbox = document.getElementById('btn-clear-sandbox');
    if (btnClearSandbox && sandboxTextarea) {
      btnClearSandbox.addEventListener('click', () => {
        sandboxTextarea.value = '';
      });
    }

    const btnCopySandbox = document.getElementById('btn-copy-sandbox');
    if (btnCopySandbox && sandboxTextarea) {
      btnCopySandbox.addEventListener('click', () => {
        navigator.clipboard.writeText(sandboxTextarea.value);
        btnCopySandbox.innerText = '已复制!';
        setTimeout(() => btnCopySandbox.innerText = '复制内容', 1500);
      });
    }
  }

  // 10. 主题与皮肤切换器
  initThemeSwitcher() {
    const pills = document.querySelectorAll('.theme-pill');
    pills.forEach(pill => {
      pill.addEventListener('click', () => {
        const theme = pill.getAttribute('data-theme');
        document.body.className = theme;
        pills.forEach(p => p.classList.remove('active'));
        pill.classList.add('active');
        this.keyboardView.render();
      });
    });
  }

  // 11. 界面导航卡片与底部全景大图鉴点击联动
  initInterfaceCards() {
    document.addEventListener('click', (e) => {
      const card = e.target.closest('.interface-card') || e.target.closest('.matrix-card');
      if (card) {
        const view = card.getAttribute('data-view');
        if (view) {
          this.switchInterface(view);
          if (card.classList.contains('matrix-card')) {
            document.querySelector('.device-stage')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
          }
        }
      }
    });
  }
}

// 页面自启动（支持 DOMContentLoaded 已触发与未触发两种情况）
function bootstrapIME() {
  if (!window.imeApp) {
    window.imeApp = new IMEApp();
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bootstrapIME);
} else {
  bootstrapIME();
}

