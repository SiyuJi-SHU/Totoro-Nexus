const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const staticDir = path.join(__dirname, '../../main/resources/static');
const readStatic = name => fs.readFileSync(path.join(staticDir, name), 'utf8');
const css = readStatic('totoro-companion.css');
const pages = ['login.html', 'index.html', 'console.html']
    .map(name => [name, readStatic(name)]);

function rule(selector) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return css.match(new RegExp(`${escaped}\\s*\\{([\\s\\S]*?)\\n\\}`))?.[1] || '';
}

test('all platform shells load the leaf favicon and versioned companion styles', () => {
    for (const [name, html] of pages) {
        assert.match(html, /<link rel="icon" href="data:image\/svg\+xml,/,
            `${name} must keep the inline leaf favicon`);
        assert.match(html, /href="\/totoro-companion\.css\?v=20260920b"/,
            `${name} must keep the companion stylesheet cache version`);
    }
});

test('login shell keeps the decorative Totoro brand mark', () => {
    assert.match(readStatic('login.html'),
        /<i class="totoro-login-mark" aria-hidden="true"><\/i>/);

    const loginMark = rule('.totoro-login-mark');
    assert.match(loginMark, /width:\s*44px;/);
    assert.match(loginMark, /height:\s*48px;/);
    assert.match(loginMark, /background-size:\s*352px 429px;/);
    assert.match(loginMark, /background-position:\s*0 0;/);
});

test('empty and agent states retain their intended sprite frames', () => {
    const empty = rule('body.totoro-nature .empty::before');
    assert.match(empty, /width:\s*64px;/);
    assert.match(empty, /height:\s*69px;/);
    assert.match(empty, /background-size:\s*512px 624px;/);
    assert.match(empty, /background-position:\s*-128px -346\.67px;/);

    assert.match(rule('.agent-card:nth-child(even) .agent-avatar'),
        /background-position-x:\s*-58px;/);

    const disabled = rule('.agent-card.is-disabled .agent-avatar');
    assert.match(disabled, /background-position:\s*-116px 0;/);
    assert.match(disabled, /grayscale\(\.65\) opacity\(\.62\)/);
});
