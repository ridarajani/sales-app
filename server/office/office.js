// The office website: the dashboard, reports and lists on a computer, signed in with the phone and PIN.
// Plain JavaScript, no build step. Everything is drawn with DOM calls, never from HTML strings.

const TOKEN_KEY = 'office.token';
const ROLE_LABELS = {
  admin: 'Admin',
  management: 'Management',
  sales_manager: 'Sales manager',
  salesman: 'Salesman',
  warehouse: 'Warehouse',
  driver: 'Driver',
  cashier: 'Cashier',
  accounts: 'Accounts',
};
const CHANNEL_LABELS = {
  supermarket: 'Supermarket',
  wholesaler: 'Wholesaler',
  minibus: 'Minibus or local market',
  upcountry: 'Upcountry',
};
const STAFF_VIEWERS = ['admin', 'management', 'sales_manager'];
const OUTBOX_VIEWERS = ['admin', 'management'];

const state = { token: null, me: null, companies: [], catalogue: null, customers: null, products: null };

// ---- Small helpers ----

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}
const svg = (tag, attrs = {}, ...children) => {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  for (const c of children) el.append(c instanceof Node ? c : String(c));
  return el;
};
const money = (n) => Number(n).toLocaleString('en-US', { maximumFractionDigits: 0 });
const dateText = (iso) => (iso ? `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}` : '');
const phoneText = (p) => (p && /^0\d{9}$/.test(p) ? `${p.slice(0, 4)} ${p.slice(4, 7)} ${p.slice(7)}` : p || '');
const companyName = (code) => state.companies.find((c) => c.code === code)?.name ?? code;

class ApiError extends Error {}

async function api(path, { method = 'GET', body, raw = false } = {}) {
  const headers = {};
  if (state.token) headers.authorization = `Bearer ${state.token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError('The server cannot be reached. Please check the internet and try again.');
  }
  if (res.status === 401 && state.token) {
    signedOut();
    throw new ApiError('Please sign in again.');
  }
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new ApiError(data.message || 'Something went wrong. Please try again.');
  }
  return raw ? res : res.json();
}

function signedOut() {
  state.token = null;
  state.me = null;
  state.catalogue = state.customers = state.products = null;
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {}
  render();
}

// ---- Sign in ----

function signInPage(root) {
  const phone = h('input', { id: 'phone', type: 'tel', autocomplete: 'username', placeholder: '0991 234 567', required: true });
  const pin = h('input', { id: 'pin', type: 'password', inputmode: 'numeric', autocomplete: 'current-password', maxlength: '4', required: true });
  const error = h('p', { class: 'error', role: 'alert' });
  const button = h('button', { class: 'primary', type: 'submit' }, 'Sign in');
  const form = h(
    'form',
    { class: 'signin' },
    h('h1', {}, 'Sales Office'),
    h('label', { for: 'phone' }, 'Phone number'),
    phone,
    h('label', { for: 'pin' }, 'PIN'),
    pin,
    error,
    button,
  );
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    error.textContent = '';
    button.disabled = true;
    try {
      const r = await api('/auth/login', { method: 'POST', body: { phone: phone.value, pin: pin.value } });
      state.token = r.token;
      state.me = r.user;
      try {
        localStorage.setItem(TOKEN_KEY, r.token);
      } catch {}
      render();
    } catch (err) {
      error.textContent = err.message;
      pin.value = '';
      pin.focus();
    } finally {
      button.disabled = false;
    }
  });
  root.replaceChildren(form);
  phone.focus();
}

// ---- Layout ----

function navLinks() {
  const links = [['#/', 'Dashboard'], ['#/alerts', 'Alerts'], ['#/search', 'Search']];
  if (state.catalogue?.groups.length) links.push(['#/reports', 'Reports']);
  links.push(['#/customers', 'Customers'], ['#/products', 'Products'], ['#/vehicles', 'Vehicles']);
  if (STAFF_VIEWERS.includes(state.me.role)) links.push(['#/staff', 'Staff']);
  if (OUTBOX_VIEWERS.includes(state.me.role)) links.push(['#/unread-sales', 'Unread trip sales']);
  return links;
}

function shell(root, active, content) {
  const nav = h(
    'nav',
    { class: 'side' },
    h('div', { class: 'brand' }, 'Sales Office'),
    navLinks().map(([href, text]) => h('a', { href, class: active === href ? 'on' : null }, text)),
    h(
      'div',
      { class: 'me' },
      h('div', {}, h('div', { class: 'name' }, state.me.name), h('span', { class: 'pill' }, ROLE_LABELS[state.me.role] ?? state.me.role)),
      h(
        'button',
        {
          class: 'danger',
          onclick: async () => {
            await api('/auth/logout', { method: 'POST' }).catch(() => {});
            signedOut();
          },
        },
        'Sign out',
      ),
    ),
  );
  root.replaceChildren(h('div', { class: 'shell' }, nav, h('main', {}, content)));
}

// ---- Dashboard ----

async function dashboardPage(main) {
  const [d, a] = await Promise.all([api('/dashboard'), api('/alerts')]);
  const hour = new Date().getHours();
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  main.append(h('div', { class: 'head' }, h('div', {}, h('h1', {}, `${greeting}, ${state.me.name.split(' ')[0]}`), h('div', { class: 'muted' }, dateText(new Date().toISOString())))));
  const urgent = a.alerts.filter((x) => x.severity !== 'info');
  if (urgent.length) {
    main.append(
      h('div', { class: 'alerts' }, urgent.slice(0, 3).map(alertCard)),
      urgent.length > 3 ? h('p', {}, h('a', { href: '#/alerts' }, `See all ${money(a.alerts.length)} alerts`)) : null,
    );
  }
  if (d.tiles.length === 0) main.append(h('p', { class: 'muted' }, 'There is nothing here for your job yet.'));
  main.append(
    h(
      'div',
      { class: 'tiles' },
      d.tiles.map((t, i) =>
        h(
          'a',
          { class: i === 0 ? 'tile first' : 'tile', href: `#/report/${t.report}?${new URLSearchParams({ from: t.from, to: t.to })}` },
          h('div', { class: 'title' }, t.title),
          h('div', { class: 'value' }, t.kind === 'money' ? `MWK ${money(t.value)}` : money(t.value)),
        ),
      ),
    ),
  );
  if (d.salesByDay?.length) main.append(h('h2', {}, 'Sales this month, before VAT'), chart(d.salesByDay));
}

// One bar for each day of the month so far; days without sales are empty.
function chart(sold) {
  const today = new Date(Date.now() + 2 * 3_600_000).toISOString().slice(0, 10);
  const days = [];
  for (let d = 1; d <= Number(today.slice(8)); d++) {
    const day = `${today.slice(0, 8)}${String(d).padStart(2, '0')}`;
    days.push({ day, subtotal: sold.find((s) => s.day === day)?.subtotal ?? 0 });
  }
  const W = 720;
  const H = 220;
  const pad = { l: 8, r: 8, t: 10, b: 24 };
  const max = Math.max(...days.map((d) => d.subtotal), 1);
  const slot = (W - pad.l - pad.r) / days.length;
  const bars = days.map((d, i) => {
    const height = ((H - pad.t - pad.b) * d.subtotal) / max;
    const x = pad.l + i * slot + slot * 0.15;
    return svg(
      'g',
      {},
      svg('rect', { class: 'bar', x, y: H - pad.b - height, width: slot * 0.7, height: Math.max(height, 1), rx: 3 }, svg('title', {}, `${dateText(d.day)}: MWK ${money(d.subtotal)}`)),
      i === 0 || (i + 1) % 5 === 0 ? svg('text', { x: x + slot * 0.35, y: H - 8, 'text-anchor': 'middle' }, String(i + 1)) : '',
    );
  });
  return h(
    'div',
    { class: 'chart' },
    svg('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Sales for each day this month' }, svg('line', { x1: pad.l, x2: W - pad.r, y1: H - pad.b, y2: H - pad.b }), ...bars),
  );
}

// ---- Reports ----

async function catalogue() {
  if (!state.catalogue) state.catalogue = await api('/reports');
  return state.catalogue;
}

async function reportsPage(main) {
  const cat = await catalogue();
  const search = h('input', { class: 'search', type: 'search', placeholder: 'Find a report', 'aria-label': 'Find a report' });
  const list = h('div');
  const draw = () => {
    const q = search.value.trim().toLowerCase();
    list.replaceChildren(
      ...cat.groups.flatMap((g) => {
        const reports = g.reports.filter((r) => !q || r.title.toLowerCase().includes(q) || r.description.toLowerCase().includes(q));
        if (reports.length === 0) return [];
        return [
          h('h2', {}, g.title),
          h(
            'div',
            { class: 'cards' },
            reports.map((r) => h('a', { class: 'card', href: `#/report/${r.key}` }, h('div', { class: 'title' }, r.title), h('div', { class: 'sub' }, r.description))),
          ),
        ];
      }),
    );
  };
  search.addEventListener('input', draw);
  main.append(h('div', { class: 'head' }, h('h1', {}, 'Reports'), search), list);
  draw();
  search.focus();
}

async function pickLists(filters) {
  if (filters.includes('customer') && !state.customers) state.customers = await api('/customers?includeInactive=true');
  if (filters.includes('product') && !state.products) state.products = await api('/products?includeInactive=true');
}

function select(id, options, value, blank) {
  return h(
    'select',
    { id },
    blank != null ? h('option', { value: '' }, blank) : null,
    options.map(([v, text]) => h('option', { value: String(v), selected: String(v) === String(value ?? '') ? true : null }, text)),
  );
}

async function reportPage(main, key, params) {
  const cat = await catalogue();
  const meta = cat.groups.flatMap((g) => g.reports).find((r) => r.key === key);
  if (!meta) {
    main.append(h('h1', {}, 'Report'), h('p', { class: 'error' }, 'This report is not open to you.'));
    return;
  }
  await pickLists(meta.filters);
  const uses = (f) => meta.filters.includes(f);
  // A filter that must be picked has no "all" choice.
  const blank = (f, all) => (meta.required.includes(f) ? 'Pick one' : all);
  const fields = {};
  const field = (name, label, input) => {
    fields[name] = input;
    return h('div', {}, h('label', { for: input.id }, label), input);
  };
  const grid = h(
    'div',
    { class: 'filters' },
    uses('dates') && field('from', 'From', h('input', { id: 'from', type: 'date', value: params.get('from') ?? '' })),
    uses('dates') && field('to', 'To', h('input', { id: 'to', type: 'date', value: params.get('to') ?? '' })),
    uses('company') && field('company', 'Company', select('company', state.me.companies.map((c) => [c, companyName(c)]), params.get('company'), blank('company', 'All my companies'))),
    uses('person') && field('person', meta.personLabel, select('person', cat.people.map((p) => [p.id, `${p.name} (${ROLE_LABELS[p.role] ?? p.role})`]), params.get('person'), 'Everyone')),
    uses('customer') && field('customer', 'Customer', select('customer', state.customers.map((c) => [c.id, c.name]), params.get('customer'), blank('customer', 'All customers'))),
    uses('channel') && field('channel', 'Channel', select('channel', Object.entries(CHANNEL_LABELS), params.get('channel'), 'All channels')),
    uses('product') && field('product', 'Product', select('product', state.products.map((p) => [p.id, `${p.code} · ${p.brand} ${p.name} ${p.packSize}`]), params.get('product'), 'All products')),
  );
  const query = () => {
    const q = new URLSearchParams();
    for (const [k, input] of Object.entries(fields)) if (input.value) q.set(k, input.value);
    return q;
  };
  const error = h('p', { class: 'error', role: 'alert' });
  const picked = h('p', { class: 'picked' });
  const result = h('div');
  const show = h('button', { class: 'primary', type: 'submit' }, 'Show');
  const download = (format) =>
    h(
      'button',
      {
        type: 'button',
        onclick: async (e) => {
          const button = e.currentTarget;
          button.disabled = true;
          error.textContent = '';
          try {
            const q = query();
            q.set('format', format);
            const res = await api(`/reports/${key}?${q}`, { raw: true });
            const name = /filename="([^"]+)"/.exec(res.headers.get('content-disposition') ?? '')?.[1] ?? `${meta.title}.${format}`;
            const url = URL.createObjectURL(await res.blob());
            const a = h('a', { href: url, download: name });
            document.body.append(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(url), 60_000);
          } catch (err) {
            error.textContent = err.message;
          } finally {
            button.disabled = false;
          }
        },
      },
      format === 'xlsx' ? 'Save as Excel' : 'Save as PDF',
    );

  const run = async () => {
    error.textContent = '';
    for (const r of meta.required) {
      if (!fields[r]?.value) {
        error.textContent = `Please pick the ${r}.`;
        result.replaceChildren();
        return;
      }
    }
    show.disabled = true;
    try {
      const q = query();
      history.replaceState(null, '', `#/report/${key}${q.size ? `?${q}` : ''}`);
      const r = await api(`/reports/${key}?${q}`);
      if (fields.from && !fields.from.value && r.from > '2000-01-01') fields.from.value = r.from;
      if (fields.to && !fields.to.value) fields.to.value = r.to;
      picked.textContent = r.picked;
      result.replaceChildren(table(r));
    } catch (err) {
      error.textContent = err.message;
    } finally {
      show.disabled = false;
    }
  };
  const form = h('form', {}, grid, h('div', { class: 'actions' }, show, download('xlsx'), download('pdf')));
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    run();
  });
  main.append(h('div', { class: 'head' }, h('div', {}, h('a', { href: '#/reports' }, '‹ Reports'), h('h1', {}, meta.title), h('div', { class: 'muted' }, meta.description))), form, error, picked, result);
  if (meta.required.every((r) => fields[r]?.value)) await run();
}

const isNum = (c) => c.kind === 'money' || c.kind === 'int' || c.kind === 'percent' || c.kind === 'qty';
function cell(c, v) {
  if (v == null || v === '') return '';
  if (c.kind === 'money' || c.kind === 'int') return money(v);
  if (c.kind === 'percent') return `${v}%`;
  if (c.kind === 'date') return dateText(String(v));
  return String(v);
}

function table(r) {
  if (r.rows.length === 0) return h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'Nothing for what was picked.'));
  const hasTotals = Object.keys(r.totals).length > 0;
  return h(
    'div',
    {},
    h(
      'div',
      { class: 'table-wrap' },
      h(
        'table',
        {},
        h('thead', {}, h('tr', {}, r.columns.map((c) => h('th', { class: isNum(c) ? 'num' : null, scope: 'col' }, c.title)))),
        h(
          'tbody',
          {},
          r.rows.map((row) =>
            h(
              'tr',
              {},
              r.columns.map((c) => h('td', { class: isNum(c) ? 'num' : c.key === 'details' || c.key === 'what' ? 'wrap' : null }, cell(c, row[c.key]))),
            ),
          ),
        ),
        hasTotals &&
          h(
            'tfoot',
            {},
            h(
              'tr',
              {},
              r.columns.map((c, i) => h('td', { class: isNum(c) ? 'num' : null }, c.key in r.totals ? cell(c, r.totals[c.key]) : i === 0 ? 'Total' : '')),
            ),
          ),
      ),
    ),
    h('p', { class: 'muted' }, r.rows.length === 1 ? '1 row.' : `${money(r.rows.length)} rows.`, r.truncated ? ' Only the first rows are shown. Pick fewer dates to see the rest.' : ''),
  );
}

// ---- Alerts ----

// Where an alert opens on the website: its report, or the unread trip sales page.
function alertLink(a) {
  if (a.screen === 'outboxProblem' && a.id && OUTBOX_VIEWERS.includes(state.me.role)) return `#/unread-sales/${a.id}`;
  if (a.screen === 'customer' && a.id && a.report === 'invoices-overdue') return `#/report/invoices-overdue?${new URLSearchParams({ customer: a.id })}`;
  if (a.report && state.catalogue?.groups.some((g) => g.reports.some((r) => r.key === a.report))) return `#/report/${a.report}`;
  return null;
}

function alertCard(a) {
  const link = alertLink(a);
  return h(
    'div',
    { class: `alert ${a.severity}` },
    h('div', {}, h('div', { class: 'title' }, a.title), h('div', { class: 'sub' }, a.detail)),
    link ? h('a', { class: 'button', href: link }, 'Open') : null,
  );
}

async function alertsPage(main) {
  const { alerts } = await api('/alerts');
  main.append(
    h('div', { class: 'head' }, h('div', {}, h('h1', {}, 'Alerts'), h('div', { class: 'muted' }, 'Things that need attention for the companies you can see.'))),
    alerts.length ? h('div', { class: 'alerts' }, alerts.map(alertCard)) : h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'No alerts. All is in order.')),
  );
}

// ---- Search ----

async function searchPage(main, params) {
  const cat = await catalogue();
  const canLedger = cat.groups.some((g) => g.reports.some((r) => r.key === 'customer-ledger'));
  const box = h('input', { type: 'search', placeholder: 'Type a shop name, phone, order, invoice, vehicle or product', 'aria-label': 'Search', value: params.get('q') ?? '' });
  const error = h('p', { class: 'error', role: 'alert' });
  const body = h('div');
  let timer;
  let asked = 0;
  const draw = async () => {
    const text = box.value.trim();
    history.replaceState(null, '', `#/search${text ? `?${new URLSearchParams({ q: text })}` : ''}`);
    error.textContent = '';
    if (text.length < 2) return body.replaceChildren(h('p', { class: 'muted' }, 'Type at least 2 letters.'));
    const mine = ++asked;
    try {
      const r = await api(`/search?${new URLSearchParams({ q: text })}`);
      if (mine !== asked) return;
      body.replaceChildren(
        ...(r.groups.length === 0
          ? [h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'Nothing found. Try fewer letters.'))]
          : r.groups.flatMap((g) => [
              h('h2', {}, g.title, ' ', h('span', { class: 'muted' }, g.count > g.items.length ? `${g.items.length} of ${money(g.count)}` : money(g.count))),
              h(
                'div',
                { class: 'cards' },
                g.items.map((it) => {
                  const href = g.key === 'customers' && canLedger ? `#/report/customer-ledger?${new URLSearchParams({ customer: it.id })}` : null;
                  return h(href ? 'a' : 'div', { class: 'card', ...(href ? { href } : {}) }, h('div', { class: 'title' }, it.title), h('div', { class: 'sub' }, it.detail));
                }),
              ),
            ])),
      );
    } catch (err) {
      error.textContent = err.message;
    }
  };
  box.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(draw, 250);
  });
  main.append(h('div', { class: 'head' }, h('h1', {}, 'Search')), box, error, body);
  setTimeout(() => box.focus());
  return draw();
}

// ---- Unread trip sales ----
// Sales a phone saved on a trip and could not read back. Admin opens the file, enters anything missing and clears it.

async function unreadListPage(main) {
  const rows = await api('/outbox-problems');
  main.append(
    h('div', { class: 'head' }, h('div', {}, h('h1', {}, 'Unread trip sales'), h('div', { class: 'muted' }, 'Sales a phone saved on a trip but could not read back. The trip cannot close until Admin clears them.'))),
    rows.length === 0
      ? h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'Nothing here. Every phone could read its sales.'))
      : h(
          'div',
          { class: 'table-wrap' },
          h(
            'table',
            {},
            h('thead', {}, h('tr', {}, ['Sent', 'Trip', 'Route', 'Leader', 'State', ''].map((t) => h('th', { scope: 'col' }, t)))),
            h(
              'tbody',
              {},
              rows.map((p) =>
                h(
                  'tr',
                  {},
                  h('td', {}, dateText(p.reportedAt)),
                  h('td', {}, p.tripNumber),
                  h('td', { class: 'wrap' }, p.route),
                  h('td', {}, p.leaderName),
                  h('td', {}, p.cleared ? h('span', { class: 'pill on' }, 'Cleared') : h('span', { class: 'pill off' }, 'To check')),
                  h('td', {}, h('a', { href: `#/unread-sales/${p.id}` }, 'Open')),
                ),
              ),
            ),
          ),
        ),
  );
}

async function unreadPage(main, id) {
  const p = await api(`/outbox-problems/${id}`);
  const error = h('p', { class: 'error', role: 'alert' });
  const download = h('button', {}, 'Download the file');
  download.addEventListener('click', async () => {
    error.textContent = '';
    try {
      const res = await api(`/outbox-problems/${id}/file`, { raw: true });
      const url = URL.createObjectURL(await res.blob());
      const a = h('a', { href: url, download: `${p.tripNumber}-unread-sales-${id}.json` });
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) {
      error.textContent = err.message;
    }
  });
  const facts = [
    ['Trip', `${p.tripNumber} · ${p.route}`],
    ['Leader', p.leaderName],
    ['Sent by', `${p.reportedBy} on ${dateText(p.reportedAt)}`],
    ['File', `${p.fileName || 'No name'} · ${money(Math.ceil(p.fileSize / 1024))} KB`],
    p.note ? ['Note from the phone', p.note] : null,
    p.cleared ? ['Cleared', `${p.clearedBy} on ${dateText(p.clearedAt)}: ${p.clearNote}`] : null,
  ].filter(Boolean);
  main.append(
    h('div', { class: 'head' }, h('div', {}, h('a', { href: '#/unread-sales' }, '‹ Unread trip sales'), h('h1', {}, `Unread sales on trip ${p.tripNumber}`))),
    h('div', { class: 'card' }, facts.map(([k, v]) => h('div', { class: 'fact' }, h('label', {}, k), h('div', {}, v)))),
    h('div', { class: 'actions' }, download),
    error,
  );
  if (p.canClear) {
    const note = h('input', { id: 'clear-note', placeholder: 'For example: entered the 2 missing sales by hand' });
    const button = h('button', { class: 'primary', type: 'submit' }, 'Clear');
    const form = h('form', { class: 'card' }, h('label', { for: 'clear-note' }, 'What did you do about these sales?'), note, h('div', { class: 'actions' }, button));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      error.textContent = '';
      button.disabled = true;
      try {
        await api(`/outbox-problems/${id}/clear`, { method: 'POST', body: { note: note.value } });
        render();
      } catch (err) {
        error.textContent = err.message;
        button.disabled = false;
      }
    });
    main.append(h('p', { class: 'muted' }, 'Look at the file, enter any sale that is missing, then clear it so the trip can close.'), form);
  }
}

// ---- Lists ----

function listPage(main, title, { load, columns, searchable = true }) {
  const search = h('input', { class: 'search', type: 'search', placeholder: `Find ${title.toLowerCase()}`, 'aria-label': `Find ${title.toLowerCase()}` });
  const error = h('p', { class: 'error', role: 'alert' });
  const body = h('div');
  let timer;
  const draw = async () => {
    try {
      const rows = await load(search.value.trim());
      body.replaceChildren(
        rows.length === 0
          ? h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'Nothing found.'))
          : h(
              'div',
              { class: 'table-wrap' },
              h(
                'table',
                {},
                h('thead', {}, h('tr', {}, columns.map(([t]) => h('th', { scope: 'col' }, t)))),
                h('tbody', {}, rows.map((row) => h('tr', {}, columns.map(([, f]) => h('td', {}, f(row)))))),
              ),
            ),
        h('p', { class: 'muted' }, `${money(rows.length)} found.`),
      );
    } catch (err) {
      error.textContent = err.message;
    }
  };
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(draw, 250);
  });
  main.append(h('div', { class: 'head' }, h('h1', {}, title), searchable ? search : null), error, body);
  return draw();
}

const onOff = (active) => (active ? null : h('span', { class: 'pill off' }, 'Off'));
const q = (path, search, extra = '') => `${path}?${new URLSearchParams({ ...(search ? { q: search } : {}), ...Object.fromEntries(new URLSearchParams(extra)) })}`;

async function customersPage(main) {
  const cat = await catalogue();
  const canLedger = cat.groups.some((g) => g.reports.some((r) => r.key === 'customer-ledger'));
  return listPage(main, 'Customers', {
    load: (s) => api(q('/customers', s, 'includeInactive=true')),
    columns: [
      ['Name', (c) => [c.name, ' ', onOff(c.active)]],
      ['Channel', (c) => CHANNEL_LABELS[c.channel] ?? c.channel],
      ['Area', (c) => c.city || c.area],
      ['Phone', (c) => phoneText(c.phone)],
      [
        'Companies',
        (c) =>
          c.companies.map((co, i) => [
            i ? h('br') : null,
            `${co.code} · ${co.paymentTermsDays ? `${co.paymentTermsDays} days` : 'cash'}`,
            co.creditLimit != null ? ` · limit MWK ${money(co.creditLimit)}` : '',
            canLedger ? [' · ', h('a', { href: `#/report/customer-ledger?${new URLSearchParams({ company: co.code, customer: c.id })}` }, 'Ledger')] : null,
          ]),
      ],
    ],
  });
}

function productsPage(main) {
  return listPage(main, 'Products', {
    load: (s) => api(q('/products', s, 'includeInactive=true')),
    columns: [
      ['Code', (p) => p.code],
      ['Product', (p) => [`${p.brand} ${p.name} ${p.packSize}`, ' ', onOff(p.active)]],
      ['Company', (p) => companyName(p.companyCode)],
      ['Pieces per carton', (p) => p.piecesPerCarton],
      ['VAT', (p) => (p.vatExempt ? 'Exempt' : '17.5%')],
      ['Low-stock level', (p) => (p.lowStockCartons != null ? `${p.lowStockCartons} ctn` : '')],
    ],
  });
}

function vehiclesPage(main) {
  return listPage(main, 'Vehicles', {
    searchable: false,
    load: () => api('/vehicles'),
    columns: [
      ['Plate', (v) => [v.plate, ' ', onOff(v.active), v.dormant ? h('span', { class: 'pill' }, 'Not mine') : null]],
      ['Description', (v) => v.description],
      ['Warehouse', (v) => (v.facilityCode ? `Facility ${v.facilityCode.slice(1)}` : 'Both')],
      ['Usual driver', (v) => v.defaultDriverName ?? ''],
    ],
  });
}

function staffPage(main) {
  return listPage(main, 'Staff', {
    load: async (s) => {
      const all = await api('/users');
      const t = s.toLowerCase();
      return all.filter((u) => !t || u.name.toLowerCase().includes(t) || u.phone.includes(t.replace(/\s/g, '')));
    },
    columns: [
      ['Name', (u) => [u.name, ' ', onOff(u.active)]],
      ['Job', (u) => h('span', { class: 'pill' }, ROLE_LABELS[u.role] ?? u.role)],
      ['Phone', (u) => phoneText(u.phone)],
      ['Companies', (u) => u.companies.map(companyName).join(', ')],
      ['Sales manager', (u) => [...new Set(u.managers.map((m) => m.managerName))].join(', ') || u.managerName || ''],
    ],
  });
}

// ---- Router ----

const PAGES = {
  '#/': dashboardPage,
  '#/reports': reportsPage,
  '#/customers': customersPage,
  '#/products': productsPage,
  '#/vehicles': vehiclesPage,
  '#/staff': staffPage,
  '#/alerts': alertsPage,
  '#/unread-sales': unreadListPage,
};

let drawing = 0;
async function render() {
  const root = document.getElementById('app');
  if (!state.token) return signInPage(root);
  const mine = ++drawing;
  try {
    if (!state.me) state.me = await api('/auth/me');
    if (!state.companies.length) state.companies = await api('/companies');
    await catalogue();
  } catch (err) {
    if (state.token) root.replaceChildren(h('p', { class: 'error' }, err.message));
    return;
  }
  const [path, search] = (location.hash || '#/').split('?');
  const main = h('div');
  const report = /^#\/report\/([a-z0-9-]+)$/.exec(path);
  const unread = /^#\/unread-sales\/(\d+)$/.exec(path);
  shell(root, report ? '#/reports' : unread ? '#/unread-sales' : path === '#/search' || PAGES[path] ? path : '#/', main);
  main.append(h('p', { class: 'loading' }, 'Loading…'));
  const page = h('div');
  try {
    if (report) await reportPage(page, report[1], new URLSearchParams(search ?? ''));
    else if (unread) await unreadPage(page, unread[1]);
    else if (path === '#/search') await searchPage(page, new URLSearchParams(search ?? ''));
    else await (PAGES[path] ?? dashboardPage)(page);
  } catch (err) {
    page.append(h('p', { class: 'error' }, err.message));
  }
  if (mine === drawing) main.replaceChildren(page);
}

try {
  state.token = localStorage.getItem(TOKEN_KEY);
} catch {}
window.addEventListener('hashchange', render);
render();
