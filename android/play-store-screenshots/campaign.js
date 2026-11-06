function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className || "";
  if (text) node.textContent = text;
  return node;
}

function base(feature = false, index = 0) {
  const asset = element("article", "asset" + (feature ? " feature" : ""));
  const ornaments = element("img", "ornaments");
  ornaments.src = feature ? "sources/feature-ornaments.svg" : "sources/ornaments.svg";
  ornaments.alt = "";
  asset.append(
    ornaments,
    element("div", "brand", config.app.name),
    element("div", "index", feature ? "" : `${String(index + 1).padStart(2, "0")} / 05`),
  );
  return asset;
}

function addCopy(asset, headline, subtext) {
  const copy = element("div", "copy");
  copy.append(element("h1", "headline", headline), element("p", "subtext", subtext));
  asset.append(copy);
}

function addFigure(asset, source, crop, layout, caption) {
  const [x, y, width, height] = crop;
  const scale = layout.width / width;
  const figure = element("figure", "figure");
  Object.assign(figure.style, {
    left: `${layout.left}px`, top: `${layout.top}px`, width: `${layout.width}px`,
  });
  const capture = element("div", "capture");
  capture.style.height = `${height * scale}px`;
  const img = element("img");
  img.src = `sources/${source}`;
  img.alt = `Real GitSync.md Android screen: ${caption}`;
  Object.assign(img.style, {
    width: `${1080 * scale}px`, height: `${2400 * scale}px`,
    left: `${-x * scale}px`, top: `${-y * scale}px`,
  });
  capture.append(img);
  figure.append(capture);
  if (caption) figure.append(element("figcaption", "caption", caption));
  asset.append(figure);
}

function createSlide(slide, index) {
  const asset = base(false, index);
  if (index === 0) asset.classList.add("hero");
  if (index === 4) asset.classList.add("staging");
  addCopy(asset, slide.headline, slide.subtext);
  addFigure(asset, slide.source, slide.crop, slide.figure, slide.caption);
  if (slide.sketch) {
    const sketch = element("div", "sketch");
    // Repository-owned SVG artwork reused from the approved iOS editorial set.
    sketch.innerHTML = index === 4
      ? campaignArt[slide.sketch].replace(">pull</text>", ">stage</text>")
      : campaignArt[slide.sketch];
    sketch.setAttribute("aria-hidden", "true");
    asset.append(sketch);
  }
  asset.append(element("footer", "note", slide.note));
  return asset;
}

function createFeature() {
  const asset = base(true);
  addCopy(asset, "Git, wherever\nyou are.", "Pull, commit and push.\nRight from your Android.");
  addFigure(asset, "vault.png", [35, 145, 1010, 1800], { left: 676, top: 40, width: 244 });
  asset.append(element("footer", "note", "Your repositories, within reach."));
  return asset;
}

window.campaignConfig = config;
window.createSlide = createSlide;
window.createFeature = createFeature;
const params = new URLSearchParams(location.search);
if (params.has("render")) {
  document.body.classList.add("render-mode");
  const value = params.get("render");
  document.querySelector("#render-root").append(
    value === "feature" ? createFeature() : createSlide(config.slides[Number(value)], Number(value)),
  );
} else {
  config.slides.forEach((slide, index) => {
    const figure = element("figure", "preview");
    const viewport = element("div", "viewport");
    viewport.append(createSlide(slide, index));
    figure.append(viewport, element("figcaption", "", `${String(index + 1).padStart(2, "0")} / ${slide.id} · 1080 × 1920`));
    document.querySelector("#grid").append(figure);
  });
  document.querySelector("#feature-preview").append(createFeature());
  const resize = () => document.querySelectorAll(".viewport").forEach(viewport => {
    const width = viewport.id === "feature-preview" ? 1024 : 1080;
    viewport.firstElementChild.style.transform = `scale(${viewport.clientWidth / width})`;
  });
  new ResizeObserver(resize).observe(document.querySelector(".gallery"));
  resize();
}
