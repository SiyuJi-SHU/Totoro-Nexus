(() => {
  document.querySelector('.totoro-companion')?.remove();
  document.body.classList.add('totoro-nature');

  const brand = document.querySelector('.brand');
  const mark = brand?.querySelector(':scope > b');
  if (brand && mark) {
    brand.classList.add('totoro-brand');
    mark.textContent = '';
    mark.classList.add('totoro-brand-mark');
    mark.setAttribute('aria-hidden', 'true');
  }

  const makeTrail = () => {
    const trail = document.createElement('div');
    trail.className = 'totoro-nature-trail';
    trail.setAttribute('aria-hidden', 'true');

    for (const className of [
      'totoro-trail-leaf',
      'totoro-trail-branch',
      'totoro-trail-acorn',
      'totoro-trail-leaf is-light'
    ]) {
      const part = document.createElement('i');
      part.className = className;
      trail.appendChild(part);
    }
    return trail;
  };

  const sidebarFooter = document.querySelector('.sidebar-footer');
  if (sidebarFooter && !sidebarFooter.querySelector('.totoro-nature-trail')) {
    sidebarFooter.prepend(makeTrail());
  }

  const conversationNav = document.querySelector('.workbench .chat-layout > aside');
  if (conversationNav && !conversationNav.querySelector('.totoro-nature-trail')) {
    conversationNav.appendChild(makeTrail());
  }
})();
