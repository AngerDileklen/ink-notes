/* Ink Notes reader: pages a book chapter into screens, finds the word under the pen, keeps highlights.
   Injected into every chapter before it is shown. Talks to the app through the "Android" object. */
(function () {
  if (window.ink) return;
  var st = { step: 1, colW: 1, pad: 0, pages: 1, page: 0, vw: 0 };
  var styleEl = null;

  function ensureStyle() {
    if (styleEl) return styleEl;
    styleEl = document.createElement('style');
    (document.head || document.documentElement).appendChild(styleEl);
    return styleEl;
  }

  function textNodes() {
    var out = [], off = 0;
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode;
        return (p && /^(SCRIPT|STYLE|NOSCRIPT)$/.test(p.nodeName)) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    });
    var n;
    while ((n = w.nextNode())) { out.push({ n: n, s: off }); off += n.nodeValue.length; }
    return out;
  }

  /* character offset in the chapter's text for a (node, offset) boundary */
  function pos(node, offset) {
    var t = textNodes();
    if (node.nodeType === 3) {
      for (var i = 0; i < t.length; i++) if (t[i].n === node) return t[i].s + offset;
      return -1;
    }
    var child = node.childNodes[offset];
    if (child) {
      var w = document.createTreeWalker(child, NodeFilter.SHOW_TEXT, null), first = w.nextNode();
      if (first) for (var j = 0; j < t.length; j++) if (t[j].n === first) return t[j].s;
    }
    var last = null, w2 = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null), x;
    while ((x = w2.nextNode())) last = x;
    if (last) for (var k = 0; k < t.length; k++) if (t[k].n === last) return t[k].s + last.nodeValue.length;
    return -1;
  }

  function locate(offset) {      /* (node, local offset) for a chapter offset */
    var t = textNodes();
    for (var i = 0; i < t.length; i++) {
      var len = t[i].n.nodeValue.length;
      if (offset <= t[i].s + len) return { n: t[i].n, o: Math.max(0, offset - t[i].s) };
    }
    return t.length ? { n: t[t.length - 1].n, o: t[t.length - 1].n.nodeValue.length } : null;
  }

  var WORD = /[\p{L}\p{N}'’\-]/u;
  function isWordChar(c) { return c !== undefined && c !== '' && WORD.test(c); }

  function expandWord(node, off) {
    var s = node.nodeValue, a = off, b = off;
    while (a > 0 && isWordChar(s[a - 1])) a--;
    while (b < s.length && isWordChar(s[b])) b++;
    while (a < b && /['’\-]/.test(s[a])) a++;
    while (b > a && /['’\-]/.test(s[b - 1])) b--;
    return { a: a, b: b };
  }

  function caret(x, y) {
    var r = document.caretRangeFromPoint(x, y);
    if (!r || r.startContainer.nodeType !== 3) return null;
    return r;
  }

  function sentenceAround(node, a, b) {
    var p = node.parentNode;
    while (p && p !== document.body && !/^(P|DIV|LI|BLOCKQUOTE|H[1-6]|TD)$/.test(p.nodeName)) p = p.parentNode;
    var txt = (p ? p.innerText || p.textContent : node.nodeValue) || '';
    var word = node.nodeValue.substring(a, b);
    var i = txt.indexOf(word);
    if (i < 0) return txt.substring(0, 240);
    var s = Math.max(txt.lastIndexOf('.', i - 1), txt.lastIndexOf('!', i - 1), txt.lastIndexOf('?', i - 1)) + 1;
    var e = txt.length;
    ['.', '!', '?'].forEach(function (c) { var k = txt.indexOf(c, i + word.length); if (k >= 0 && k + 1 < e) e = k + 1; });
    return txt.substring(s, e).trim().substring(0, 300);
  }

  function select(range) {
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  function hlAt(node) {
    var p = node.parentNode;
    while (p && p !== document.body) { if (p.classList && p.classList.contains('ink-hl')) return p.getAttribute('data-id'); p = p.parentNode; }
    return '';
  }

  var ink = window.ink = {
    /* lays the chapter out in columns, one per screen; returns the number of screens */
    layout: function (fontPx, family, lineH, margin) {
      var vw = window.innerWidth, vh = window.innerHeight;
      var colW = vw - 2 * margin, colH = vh - 2 * margin, gap = 2 * margin;
      st.step = colW + gap; st.colW = colW; st.pad = margin; st.vw = vw;      /* innerWidth grows with the columns afterwards: keep the screen's width */
      var fam = family === 'sans' ? 'Roboto, "Noto Sans", Arial, sans-serif' : '"Noto Serif", Georgia, "Times New Roman", serif';
      ensureStyle().textContent =
        'html{margin:0!important;padding:0!important;overflow:hidden!important;width:' + vw + 'px!important;height:' + vh + 'px!important;background:#fff!important;-webkit-text-size-adjust:none!important;touch-action:none!important}' +
        'body{margin:' + margin + 'px!important;padding:0!important;width:' + colW + 'px!important;height:' + colH + 'px!important;' +
        '-webkit-column-width:' + colW + 'px!important;column-width:' + colW + 'px!important;-webkit-column-gap:' + gap + 'px!important;column-gap:' + gap + 'px!important;column-fill:auto!important;' +
        'color:#000!important;background:#fff!important;font-family:' + fam + '!important;font-size:' + fontPx + 'px!important;line-height:' + lineH + '!important;' +
        'word-wrap:break-word;overflow-wrap:break-word;touch-action:none!important;-webkit-touch-callout:none;}' +
        'body p,body li,body div,body td,body th,body blockquote,body dd,body dt,body span{font-size:1em!important;line-height:inherit!important}' +
        'body p,body li,body div,body blockquote,body h1,body h2,body h3,body h4,body h5,body h6,body span,body a,body td{font-family:' + fam + '!important;color:#000!important}' +
        'body *{max-width:' + colW + 'px!important;box-sizing:border-box}' +
        'body img,body svg,body video{max-width:100%!important;max-height:' + colH + 'px!important;width:auto;height:auto!important;object-fit:contain;break-inside:avoid}' +
        'body h1,body h2,body h3,body h4{break-after:avoid;line-height:1.25!important}' +
        'body p{orphans:2;widows:2}body pre{white-space:pre-wrap;font-family:monospace!important}body table{max-width:100%!important}' +
        'a{color:#000!important;text-decoration:underline}' +
        '::selection{background:#b4b4b4!important;color:#000!important}.ink-hl{background:#cdcdcd!important;color:#000!important}';
      var total = document.documentElement.scrollWidth;
      st.pages = total <= vw + 2 ? 1 : Math.max(1, Math.ceil((total - margin + gap) / st.step - 0.02));
      if (st.page >= st.pages) st.page = st.pages - 1;
      window.scrollTo(st.page * st.step, 0);
      document.documentElement.style.visibility = 'visible';
      return st.pages;
    },
    pages: function () { return st.pages; },
    goto: function (p) {
      st.page = Math.max(0, Math.min(st.pages - 1, p));
      window.scrollTo(st.page * st.step, 0);
      return st.page;
    },
    pageOfElement: function (id) {
      var el = document.getElementById(id) || document.getElementsByName(id)[0];
      if (!el) return 0;
      var x = el.getBoundingClientRect().left + window.scrollX;
      return Math.max(0, Math.floor((x - st.pad) / st.step + 0.0001));
    },
    pageOfOffset: function (offset) {
      var l = locate(offset);
      if (!l) return 0;
      var r = document.createRange(); r.setStart(l.n, Math.min(l.o, l.n.nodeValue.length)); r.collapse(true);
      var rc = r.getClientRects()[0] || r.getBoundingClientRect();
      var x = rc.left + window.scrollX;
      return Math.max(0, Math.floor((x - st.pad) / st.step + 0.0001));
    },
    chapterText: function () { return (document.body.innerText || '').trim(); },

    /* the word under (x, y), or null when the pen is on blank paper */
    wordAt: function (x, y) {
      var r = caret(x, y);
      if (!r) return null;
      var n = r.startContainer, w = expandWord(n, r.startOffset);
      if (w.b <= w.a) return null;
      var wr = document.createRange(); wr.setStart(n, w.a); wr.setEnd(n, w.b);
      var rects = wr.getClientRects(), hit = false;
      for (var i = 0; i < rects.length; i++) {
        var q = rects[i];
        if (x >= q.left - 14 && x <= q.right + 14 && y >= q.top - 14 && y <= q.bottom + 14) hit = true;
      }
      if (!hit) return null;
      select(wr);
      var s = pos(n, w.a);
      return { word: n.nodeValue.substring(w.a, w.b), sentence: sentenceAround(n, w.a, w.b), start: s, end: s + (w.b - w.a), hl: hlAt(n) };
    },

    /* the text between two points, grown to whole words */
    selectBetween: function (x0, y0, x1, y1) {
      var a = caret(x0, y0), b = caret(x1, y1);
      if (!a || !b) return null;
      var r = document.createRange();
      r.setStart(a.startContainer, a.startOffset); r.setEnd(b.startContainer, b.startOffset);
      if (r.collapsed) { r.setStart(b.startContainer, b.startOffset); r.setEnd(a.startContainer, a.startOffset); }
      if (r.collapsed) return null;
      var ws = expandWord(r.startContainer, r.startOffset), we = expandWord(r.endContainer, r.endOffset);
      r.setStart(r.startContainer, ws.a); r.setEnd(r.endContainer, we.b);
      select(r);
      var s = pos(r.startContainer, r.startOffset), e = pos(r.endContainer, r.endOffset);
      return { text: r.toString().replace(/\s+/g, ' ').trim(), start: s, end: e };
    },
    clearSelection: function () { window.getSelection().removeAllRanges(); },

    /* highlights are kept as character offsets of the chapter text */
    addHighlight: function (start, end, id) {
      var t = textNodes(), segs = [];
      for (var i = 0; i < t.length; i++) {
        var ns = t[i].s, ne = ns + t[i].n.nodeValue.length;
        var a = Math.max(start, ns), b = Math.min(end, ne);
        if (b > a) segs.push({ n: t[i].n, a: a - ns, b: b - ns });
      }
      for (var k = segs.length - 1; k >= 0; k--) {          /* last first: wrapping splits a text node, earlier ones are not touched */
        var r = document.createRange(); r.setStart(segs[k].n, segs[k].a); r.setEnd(segs[k].n, segs[k].b);
        var sp = document.createElement('span'); sp.className = 'ink-hl'; sp.setAttribute('data-id', id);
        try { r.surroundContents(sp); } catch (e) { }
      }
      return segs.length > 0;
    },
    applyHighlights: function (list) {
      ink.removeHighlights();
      for (var i = 0; i < list.length; i++) ink.addHighlight(list[i].s, list[i].e, String(list[i].id));
    },
    removeHighlights: function () {
      var spans = document.querySelectorAll('span.ink-hl');
      for (var i = 0; i < spans.length; i++) { var s = spans[i], p = s.parentNode; while (s.firstChild) p.insertBefore(s.firstChild, s); p.removeChild(s); }
      document.body.normalize();
    },
    removeHighlight: function (id) {
      var spans = document.querySelectorAll('span.ink-hl[data-id="' + id + '"]');
      for (var i = 0; i < spans.length; i++) { var s = spans[i], p = s.parentNode; while (s.firstChild) p.insertBefore(s.firstChild, s); p.removeChild(s); }
      document.body.normalize();
    },

    /* first match of a phrase from a character offset on: select it and return where it starts */
    find: function (q, from) {
      var all = '', t = textNodes();
      for (var i = 0; i < t.length; i++) all += t[i].n.nodeValue;
      var at = all.toLowerCase().indexOf(q.toLowerCase(), from || 0);
      if (at < 0) return -1;
      var a = locate(at), b = locate(at + q.length);
      if (a && b) { var r = document.createRange(); r.setStart(a.n, Math.min(a.o, a.n.nodeValue.length)); r.setEnd(b.n, Math.min(b.o, b.n.nodeValue.length)); select(r); }
      return at;
    }
  };

  /* touches: a tap turns the page at the edges or looks up a word; a drag selects text */
  var d = null;
  function down(e) { d = { x: e.clientX, y: e.clientY, t: Date.now() }; }
  function up(e) {
    if (!d) return;
    var dx = e.clientX - d.x, dy = e.clientY - d.y, dist = Math.sqrt(dx * dx + dy * dy), dt = Date.now() - d.t, x0 = d.x, y0 = d.y;
    d = null;
    var vw = st.vw || window.innerWidth;
    if (dist < 8 && dt < 700) {
      var a = document.elementFromPoint(e.clientX, e.clientY);
      while (a && a.nodeName !== 'A' && a !== document.body) a = a.parentNode;
      if (a && a.nodeName === 'A' && a.getAttribute('href')) { Android.onLink(a.getAttribute('href')); return; }
      if (e.clientX < vw * 0.12) { Android.onTap('left'); return; }
      if (e.clientX > vw * 0.88) { Android.onTap('right'); return; }
      var w = ink.wordAt(e.clientX, e.clientY);
      if (w) Android.onWord(w.word, w.sentence, w.start, w.end, w.hl); else { ink.clearSelection(); Android.onTap('blank'); }
    } else if (dist >= 8) {
      if (Math.abs(dx) > vw * 0.5 && Math.abs(dy) < 60) { Android.onTap(dx < 0 ? 'right' : 'left'); return; }   /* a long sideways sweep turns the page */
      var s = ink.selectBetween(x0, y0, e.clientX, e.clientY);
      if (s && s.text) Android.onSelection(s.text, s.start, s.end);
    }
  }
  document.addEventListener('pointerdown', down, true);
  document.addEventListener('pointerup', up, true);
  document.addEventListener('pointercancel', function () { d = null; }, true);
  document.addEventListener('contextmenu', function (e) { e.preventDefault(); }, true);
  document.addEventListener('click', function (e) { e.preventDefault(); }, true);
  document.addEventListener('selectstart', function () { }, true);
  document.documentElement.style.visibility = 'hidden';
  window.addEventListener('load', function () { Android.onLoaded(); });
})();
