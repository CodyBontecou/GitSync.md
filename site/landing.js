(() => {
  const previews = {
    repositories: {
      src: 'screenshots/hero-repo-list-660.webp',
      alt: 'GitSync.md repository list with three example repositories and their branch and commit details',
      caption: 'Your vaults and codebases. One place. Real Git.'
    },
    changes: {
      src: 'screenshots/workflow-vault-view-660.webp',
      alt: 'GitSync.md repository view showing repo health and four changed files ready for review',
      caption: 'Know your branch. Review your files. Choose your next move.'
    },
    diff: {
      src: 'screenshots/diff-view-660.webp',
      alt: 'GitSync.md diff viewer with additions in green and deletions in red',
      caption: 'Every added line. Every removed line. Before you commit.'
    }
  };
  const image = document.getElementById('app-preview');
  const caption = document.getElementById('preview-caption');
  const buttons = document.querySelectorAll('[data-preview]');
  buttons.forEach(button => {
    button.addEventListener('click', () => {
      const preview = previews[button.dataset.preview];
      if (!preview || !image || !caption) return;
      image.src = preview.src;
      image.alt = preview.alt;
      caption.textContent = preview.caption;
      buttons.forEach(item => {
        const active = item === button;
        item.classList.toggle('active', active);
        item.setAttribute('aria-pressed', String(active));
      });
    });
  });
})();
