// Load YouTube only after a visitor explicitly chooses to play a tutorial.
document.querySelectorAll('[data-video-id]').forEach(link => {
  link.addEventListener('click', event => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const id = link.dataset.videoId;
    if (!/^[\w-]{11}$/.test(id)) return;
    event.preventDefault();
    const player = document.createElement('iframe');
    player.title = link.dataset.videoTitle;
    const start = Number(link.dataset.videoStart);
    const offset = Number.isInteger(start) && start > 0 ? `&start=${start}` : '';
    player.src = `https://www.youtube-nocookie.com/embed/${id}?autoplay=1${offset}`;
    player.allow = 'autoplay; encrypted-media; picture-in-picture; web-share';
    player.allowFullscreen = true;
    player.referrerPolicy = 'strict-origin-when-cross-origin';
    link.replaceWith(player);
    player.focus();
  });
});
