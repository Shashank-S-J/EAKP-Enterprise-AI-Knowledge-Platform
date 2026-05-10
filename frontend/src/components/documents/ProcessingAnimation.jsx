export default function ProcessingAnimation() {
  return (
    <div className="processing-3d">
      <div className="processing-cube">
        <div className="cube-face front" />
        <div className="cube-face back" />
        <div className="cube-face left" />
        <div className="cube-face right" />
        <div className="cube-face top" />
        <div className="cube-face bottom" />
      </div>
      <div className="processing-orbit">
        <div className="orbit-particle" />
        <div className="orbit-particle" />
        <div className="orbit-particle" />
      </div>
    </div>
  );
}

