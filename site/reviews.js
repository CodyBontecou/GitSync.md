(() => {
  const wall = document.querySelector('[data-reviews-wall]');
  const button = document.querySelector('.reviews-pause');
  if (!wall || !button || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

  wall.querySelectorAll('.reviews-group').forEach(group => {
    const copy = group.cloneNode(true);
    copy.setAttribute('aria-hidden', 'true');
    copy.setAttribute('inert', '');
    group.parentNode.appendChild(copy);
  });
  wall.classList.add('is-animated');
  button.hidden = false;
  button.addEventListener('click', () => {
    const paused = wall.classList.toggle('is-paused');
    button.setAttribute('aria-pressed', String(paused));
    button.textContent = paused ? 'Resume scrolling' : 'Pause scrolling';
  });

  if ('IntersectionObserver' in window) {
    new IntersectionObserver(entries => {
      wall.classList.toggle('is-in-view', entries[0].isIntersecting);
    }).observe(wall);
  } else {
    wall.classList.add('is-in-view');
  }
})();
