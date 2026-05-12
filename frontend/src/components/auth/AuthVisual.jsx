import { useEffect, useRef, useCallback } from 'react';

// Neural network node
class Node {
    constructor(x, y, canvas) {
        this.x = x;
        this.y = y;
        this.originX = x;
        this.originY = y;
        this.vx = (Math.random() - 0.5) * 0.3;
        this.vy = (Math.random() - 0.5) * 0.3;
        this.radius = Math.random() * 2 + 1;
        this.canvas = canvas;
        this.opacity = Math.random() * 0.5 + 0.2;
        this.pulsePhase = Math.random() * Math.PI * 2;
        this.pulseSpeed = Math.random() * 0.02 + 0.01;
        this.highlighted = false;
    }

    update(mouse) {
        this.x += this.vx;
        this.y += this.vy;

        const w = this.canvas.width;
        const h = this.canvas.height;
        if (this.x < 0 || this.x > w) this.vx *= -1;
        if (this.y < 0 || this.y > h) this.vy *= -1;

        this.x += (this.originX - this.x) * 0.002;
        this.y += (this.originY - this.y) * 0.002;

        // Mouse repulsion with stronger interaction
        this.highlighted = false;
        if (mouse.x !== null) {
            const dx = this.x - mouse.x;
            const dy = this.y - mouse.y;
            const dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 180) {
                const force = (180 - dist) / 180;
                this.x += dx * force * 0.025;
                this.y += dy * force * 0.025;
                if (dist < 100) this.highlighted = true;
            }
        }

        this.pulsePhase += this.pulseSpeed;
        this.currentRadius = this.radius + Math.sin(this.pulsePhase) * 0.5;
        this.currentOpacity = this.opacity + Math.sin(this.pulsePhase) * 0.1;
    }
}

// Orbiting ring
class OrbRing {
    constructor(radius, speed, opacity, dashes) {
        this.radius = radius;
        this.angle = Math.random() * Math.PI * 2;
        this.speed = speed;
        this.opacity = opacity;
        this.tilt = Math.random() * 0.3 + 0.7;
        this.dashes = dashes;
    }

    update() {
        this.angle += this.speed;
    }

    draw(ctx, cx, cy) {
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(this.angle);
        ctx.scale(1, this.tilt);
        ctx.beginPath();
        ctx.arc(0, 0, this.radius, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(207, 188, 255, ${this.opacity})`;
        ctx.lineWidth = 1;
        if (this.dashes) ctx.setLineDash(this.dashes);
        ctx.stroke();
        ctx.restore();
    }
}

// Data stream particle — shoots across the screen
class DataStream {
    constructor(cw, ch) {
        this.reset(cw, ch);
    }

    reset(cw, ch) {
        // Start from edges
        const side = Math.floor(Math.random() * 4);
        if (side === 0) { this.x = 0; this.y = Math.random() * ch; this.vx = Math.random() * 2 + 1; this.vy = (Math.random() - 0.5) * 0.5; }
        else if (side === 1) { this.x = cw; this.y = Math.random() * ch; this.vx = -(Math.random() * 2 + 1); this.vy = (Math.random() - 0.5) * 0.5; }
        else if (side === 2) { this.x = Math.random() * cw; this.y = 0; this.vx = (Math.random() - 0.5) * 0.5; this.vy = Math.random() * 2 + 1; }
        else { this.x = Math.random() * cw; this.y = ch; this.vx = (Math.random() - 0.5) * 0.5; this.vy = -(Math.random() * 2 + 1); }
        this.trail = [];
        this.maxTrail = Math.floor(Math.random() * 15) + 10;
        this.life = 1;
        this.decay = Math.random() * 0.003 + 0.002;
        this.size = Math.random() * 1.5 + 0.5;
        this.color = Math.random() > 0.5 ? [207, 188, 255] : [231, 195, 101];
    }

    update(cw, ch) {
        this.trail.push({ x: this.x, y: this.y });
        if (this.trail.length > this.maxTrail) this.trail.shift();
        this.x += this.vx;
        this.y += this.vy;
        this.life -= this.decay;
        if (this.life <= 0 || this.x < -50 || this.x > cw + 50 || this.y < -50 || this.y > ch + 50) {
            this.reset(cw, ch);
        }
    }

    draw(ctx) {
        // Trail
        for (let i = 0; i < this.trail.length; i++) {
            const alpha = (i / this.trail.length) * this.life * 0.3;
            ctx.beginPath();
            ctx.arc(this.trail[i].x, this.trail[i].y, this.size * (i / this.trail.length), 0, Math.PI * 2);
            ctx.fillStyle = `rgba(${this.color[0]}, ${this.color[1]}, ${this.color[2]}, ${alpha})`;
            ctx.fill();
        }
        // Head
        ctx.beginPath();
        ctx.arc(this.x, this.y, this.size * 1.5, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(${this.color[0]}, ${this.color[1]}, ${this.color[2]}, ${this.life * 0.6})`;
        ctx.fill();
        // Head glow
        ctx.beginPath();
        ctx.arc(this.x, this.y, this.size * 4, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(${this.color[0]}, ${this.color[1]}, ${this.color[2]}, ${this.life * 0.1})`;
        ctx.fill();
    }
}

// Click ripple effect
class Ripple {
    constructor(x, y) {
        this.x = x;
        this.y = y;
        this.radius = 0;
        this.maxRadius = 150 + Math.random() * 100;
        this.opacity = 0.5;
        this.speed = 2 + Math.random() * 2;
    }

    update() {
        this.radius += this.speed;
        this.opacity = 0.5 * (1 - this.radius / this.maxRadius);
        return this.radius < this.maxRadius;
    }

    draw(ctx) {
        ctx.beginPath();
        ctx.arc(this.x, this.y, this.radius, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(207, 188, 255, ${this.opacity})`;
        ctx.lineWidth = 1.5;
        ctx.stroke();
        // Inner ring
        if (this.radius > 20) {
            ctx.beginPath();
            ctx.arc(this.x, this.y, this.radius * 0.6, 0, Math.PI * 2);
            ctx.strokeStyle = `rgba(231, 195, 101, ${this.opacity * 0.5})`;
            ctx.lineWidth = 0.8;
            ctx.stroke();
        }
    }
}

export default function AuthVisual({ variant = 'login' }) {
    const canvasRef = useRef(null);
    const mouseRef = useRef({ x: null, y: null });
    const animRef = useRef(null);
    const ripplesRef = useRef([]);

    const handleMouseMove = useCallback((e) => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const rect = canvas.getBoundingClientRect();
        mouseRef.current = {
            x: (e.clientX - rect.left) * (canvas.width / rect.width),
            y: (e.clientY - rect.top) * (canvas.height / rect.height),
        };
    }, []);

    const handleMouseLeave = useCallback(() => {
        mouseRef.current = { x: null, y: null };
    }, []);

    const handleClick = useCallback((e) => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const rect = canvas.getBoundingClientRect();
        const x = (e.clientX - rect.left) * (canvas.width / rect.width);
        const y = (e.clientY - rect.top) * (canvas.height / rect.height);
        ripplesRef.current.push(new Ripple(x, y));
    }, []);

    useEffect(() => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const ctx = canvas.getContext('2d');

        const resize = () => {
            const dpr = window.devicePixelRatio || 1;
            const rect = canvas.parentElement.getBoundingClientRect();
            canvas.width = rect.width * dpr;
            canvas.height = rect.height * dpr;
            canvas.style.width = rect.width + 'px';
            canvas.style.height = rect.height + 'px';
            ctx.scale(dpr, dpr);
        };
        resize();
        window.addEventListener('resize', resize);

        const w = () => canvas.width / (window.devicePixelRatio || 1);
        const h = () => canvas.height / (window.devicePixelRatio || 1);

        // Create nodes
        const nodeCount = 70;
        const nodes = [];
        for (let i = 0; i < nodeCount; i++) {
            nodes.push(new Node(
                Math.random() * w(),
                Math.random() * h(),
                { get width() { return w(); }, get height() { return h(); } }
            ));
        }

        // Create orb rings
        const baseR = Math.min(w(), h()) * 0.18;
        const rings = [
            new OrbRing(baseR * 1.8, 0.003, 0.12, [8, 6]),
            new OrbRing(baseR * 1.5, -0.005, 0.18, null),
            new OrbRing(baseR * 1.2, 0.004, 0.25, [4, 8]),
            new OrbRing(baseR * 0.9, -0.007, 0.3, null),
        ];

        // For register variant, add extra expanding rings
        if (variant === 'register') {
            rings.push(new OrbRing(baseR * 2.2, 0.002, 0.08, [12, 8]));
            rings.push(new OrbRing(baseR * 2.6, -0.0015, 0.05, [6, 12]));
        }

        // Floating energy particles on rings
        const orbitParticles = [];
        for (let i = 0; i < 16; i++) {
            orbitParticles.push({
                ringIdx: Math.floor(Math.random() * rings.length),
                angle: Math.random() * Math.PI * 2,
                speed: (Math.random() * 0.01 + 0.005) * (Math.random() > 0.5 ? 1 : -1),
                size: Math.random() * 2 + 1,
                opacity: Math.random() * 0.6 + 0.3,
            });
        }

        // Data streams
        const dataStreams = [];
        const streamCount = variant === 'register' ? 10 : 7;
        for (let i = 0; i < streamCount; i++) {
            dataStreams.push(new DataStream(w(), h()));
        }

        // Hexagonal grid points (subtle background pattern)
        const hexPoints = [];
        const hexSpacing = 60;
        for (let row = 0; row < h() / hexSpacing + 1; row++) {
            for (let col = 0; col < w() / hexSpacing + 1; col++) {
                const offsetX = row % 2 === 0 ? 0 : hexSpacing / 2;
                hexPoints.push({
                    x: col * hexSpacing + offsetX,
                    y: row * hexSpacing,
                    baseOpacity: 0.03,
                });
            }
        }

        // Energy wave state
        let energyWaveRadius = 0;
        let energyWaveActive = false;
        let energyWaveCx = 0;
        let energyWaveCy = 0;
        let lastMouseDist = 0;

        let time = 0;
        const TARGET_FPS = 30;
        const FRAME_INTERVAL = 1000 / TARGET_FPS;
        let lastFrameTime = 0;

        const draw = (timestamp) => {
            // FPS throttle — skip frames to maintain ~30fps
            if (timestamp - lastFrameTime < FRAME_INTERVAL) {
                animRef.current = requestAnimationFrame(draw);
                return;
            }
            lastFrameTime = timestamp;

            time++;
            const cw = w();
            const ch = h();
            const centerX = cw / 2;
            const centerY = ch / 2;
            const mouse = mouseRef.current;

            ctx.clearRect(0, 0, cw, ch);

            // Background radial glow
            const bgGrad = ctx.createRadialGradient(centerX, centerY, 0, centerX, centerY, cw * 0.6);
            bgGrad.addColorStop(0, 'rgba(103, 80, 164, 0.06)');
            bgGrad.addColorStop(0.5, 'rgba(77, 68, 101, 0.03)');
            bgGrad.addColorStop(1, 'transparent');
            ctx.fillStyle = bgGrad;
            ctx.fillRect(0, 0, cw, ch);

            // Hexagonal grid (subtle)
            hexPoints.forEach(p => {
                let alpha = p.baseOpacity;
                // Brighten near mouse
                if (mouse.x !== null) {
                    const dx = p.x - mouse.x;
                    const dy = p.y - mouse.y;
                    const dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < 150) {
                        alpha += (1 - dist / 150) * 0.08;
                    }
                }
                // Pulse wave
                const waveDist = Math.sqrt((p.x - centerX) ** 2 + (p.y - centerY) ** 2);
                alpha += Math.sin(time * 0.015 - waveDist * 0.02) * 0.015;

                ctx.beginPath();
                ctx.arc(p.x, p.y, 1, 0, Math.PI * 2);
                ctx.fillStyle = `rgba(207, 188, 255, ${Math.max(0, alpha)})`;
                ctx.fill();
            });

            // Mouse-reactive glow (enhanced)
            if (mouse.x !== null) {
                const mouseGlow = ctx.createRadialGradient(mouse.x, mouse.y, 0, mouse.x, mouse.y, 250);
                mouseGlow.addColorStop(0, 'rgba(207, 188, 255, 0.08)');
                mouseGlow.addColorStop(0.5, 'rgba(103, 80, 164, 0.04)');
                mouseGlow.addColorStop(1, 'transparent');
                ctx.fillStyle = mouseGlow;
                ctx.fillRect(0, 0, cw, ch);

                // Detect fast mouse movement → trigger energy wave
                const mouseDist = Math.sqrt(mouse.x ** 2 + mouse.y ** 2);
                if (Math.abs(mouseDist - lastMouseDist) > 30 && !energyWaveActive) {
                    energyWaveActive = true;
                    energyWaveRadius = 0;
                    energyWaveCx = mouse.x;
                    energyWaveCy = mouse.y;
                }
                lastMouseDist = mouseDist;
            }

            // Energy wave
            if (energyWaveActive) {
                energyWaveRadius += 3;
                const waveAlpha = 0.2 * (1 - energyWaveRadius / 400);
                if (waveAlpha > 0) {
                    ctx.beginPath();
                    ctx.arc(energyWaveCx, energyWaveCy, energyWaveRadius, 0, Math.PI * 2);
                    ctx.strokeStyle = `rgba(207, 188, 255, ${waveAlpha})`;
                    ctx.lineWidth = 2;
                    ctx.stroke();
                } else {
                    energyWaveActive = false;
                }
            }

            // Update & draw data streams
            dataStreams.forEach(s => {
                s.update(cw, ch);
                s.draw(ctx);
            });

            // Update & draw nodes
            nodes.forEach(n => n.update(mouse, time));

            // Draw connections (enhanced with glow for highlighted)
            const maxDist = 130;
            for (let i = 0; i < nodes.length; i++) {
                for (let j = i + 1; j < nodes.length; j++) {
                    const dx = nodes[i].x - nodes[j].x;
                    const dy = nodes[i].y - nodes[j].y;
                    const dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < maxDist) {
                        const alpha = (1 - dist / maxDist) * 0.15;
                        const isHighlighted = nodes[i].highlighted && nodes[j].highlighted;
                        ctx.beginPath();
                        ctx.moveTo(nodes[i].x, nodes[i].y);
                        ctx.lineTo(nodes[j].x, nodes[j].y);
                        if (isHighlighted) {
                            ctx.strokeStyle = `rgba(231, 195, 101, ${alpha * 3})`;
                            ctx.lineWidth = 1.5;
                        } else {
                            ctx.strokeStyle = `rgba(207, 188, 255, ${alpha})`;
                            ctx.lineWidth = 0.5;
                        }
                        ctx.stroke();
                    }
                }
            }

            // Draw nodes (with highlight glow)
            nodes.forEach(n => {
                ctx.beginPath();
                ctx.arc(n.x, n.y, n.currentRadius, 0, Math.PI * 2);
                if (n.highlighted) {
                    ctx.fillStyle = `rgba(231, 195, 101, ${n.currentOpacity + 0.3})`;
                    ctx.fill();
                    // Glow ring
                    ctx.beginPath();
                    ctx.arc(n.x, n.y, n.currentRadius * 4, 0, Math.PI * 2);
                    ctx.fillStyle = `rgba(231, 195, 101, 0.06)`;
                    ctx.fill();
                } else {
                    ctx.fillStyle = `rgba(207, 188, 255, ${n.currentOpacity})`;
                    ctx.fill();
                }
            });

            // Draw orb rings
            rings.forEach(r => {
                r.update();
                r.draw(ctx, centerX, centerY);
            });

            // Draw orbit particles
            orbitParticles.forEach(p => {
                p.angle += p.speed;
                const ring = rings[p.ringIdx];
                const px = centerX + Math.cos(p.angle) * ring.radius;
                const py = centerY + Math.sin(p.angle) * ring.radius * ring.tilt;
                ctx.beginPath();
                ctx.arc(px, py, p.size, 0, Math.PI * 2);
                ctx.fillStyle = `rgba(231, 195, 101, ${p.opacity})`;
                ctx.fill();
                ctx.beginPath();
                ctx.arc(px, py, p.size * 3, 0, Math.PI * 2);
                ctx.fillStyle = `rgba(231, 195, 101, ${p.opacity * 0.15})`;
                ctx.fill();
            });

            // Scanning line effect (horizontal)
            const scanY = (time * 0.5) % ch;
            const scanGrad = ctx.createLinearGradient(0, scanY - 30, 0, scanY + 30);
            scanGrad.addColorStop(0, 'transparent');
            scanGrad.addColorStop(0.5, 'rgba(207, 188, 255, 0.03)');
            scanGrad.addColorStop(1, 'transparent');
            ctx.fillStyle = scanGrad;
            ctx.fillRect(0, scanY - 30, cw, 60);

            // Central orb glow (enhanced breathing)
            const orbPulse = Math.sin(time * 0.02) * 0.15 + 0.85;
            const coreR = baseR * 0.35 * orbPulse;
            const coreGrad = ctx.createRadialGradient(centerX, centerY, 0, centerX, centerY, coreR);
            coreGrad.addColorStop(0, 'rgba(207, 188, 255, 0.3)');
            coreGrad.addColorStop(0.3, 'rgba(207, 188, 255, 0.15)');
            coreGrad.addColorStop(0.6, 'rgba(103, 80, 164, 0.08)');
            coreGrad.addColorStop(1, 'transparent');
            ctx.fillStyle = coreGrad;
            ctx.beginPath();
            ctx.arc(centerX, centerY, coreR, 0, Math.PI * 2);
            ctx.fill();

            // Outer halo pulse
            const haloR = baseR * 0.5 + Math.sin(time * 0.01) * baseR * 0.05;
            ctx.beginPath();
            ctx.arc(centerX, centerY, haloR, 0, Math.PI * 2);
            ctx.strokeStyle = `rgba(207, 188, 255, ${0.05 + Math.sin(time * 0.015) * 0.03})`;
            ctx.lineWidth = 0.5;
            ctx.stroke();

            // Central icon glow ring
            const iconR = baseR * 0.28;
            ctx.beginPath();
            ctx.arc(centerX, centerY, iconR, 0, Math.PI * 2);
            ctx.fillStyle = 'rgba(33, 31, 36, 0.8)';
            ctx.fill();
            ctx.strokeStyle = `rgba(207, 188, 255, ${0.3 + Math.sin(time * 0.03) * 0.15})`;
            ctx.lineWidth = 1.5;
            ctx.stroke();

            // Rotating arc segments around center
            for (let i = 0; i < 3; i++) {
                const arcR = iconR + 8 + i * 6;
                const startAngle = time * (0.008 + i * 0.003) + (i * Math.PI * 2 / 3);
                const arcLen = Math.PI * 0.4;
                ctx.beginPath();
                ctx.arc(centerX, centerY, arcR, startAngle, startAngle + arcLen);
                ctx.strokeStyle = `rgba(207, 188, 255, ${0.15 - i * 0.03})`;
                ctx.lineWidth = 1;
                ctx.stroke();
            }

            // Mouse attraction line to center
            if (mouse.x !== null) {
                const dx = mouse.x - centerX;
                const dy = mouse.y - centerY;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < cw * 0.4) {
                    const alpha = (1 - dist / (cw * 0.4)) * 0.1;
                    ctx.beginPath();
                    ctx.moveTo(centerX, centerY);
                    ctx.lineTo(mouse.x, mouse.y);
                    ctx.strokeStyle = `rgba(207, 188, 255, ${alpha})`;
                    ctx.lineWidth = 1;
                    ctx.setLineDash([4, 6]);
                    ctx.stroke();
                    ctx.setLineDash([]);

                    // Energy dots along the line
                    const dotCount = Math.floor(dist / 30);
                    for (let i = 0; i < dotCount; i++) {
                        const t = (i / dotCount + time * 0.005) % 1;
                        const dotX = centerX + dx * t;
                        const dotY = centerY + dy * t;
                        ctx.beginPath();
                        ctx.arc(dotX, dotY, 1.5, 0, Math.PI * 2);
                        ctx.fillStyle = `rgba(231, 195, 101, ${alpha * 3 * (1 - t)})`;
                        ctx.fill();
                    }
                }
            }

            // Click ripples
            ripplesRef.current = ripplesRef.current.filter(r => {
                const alive = r.update();
                r.draw(ctx);
                return alive;
            });

            animRef.current = requestAnimationFrame(draw);
        };

        animRef.current = requestAnimationFrame(draw);

        return () => {
            cancelAnimationFrame(animRef.current);
            window.removeEventListener('resize', resize);
        };
    }, [variant]);

    return (
        <canvas
            ref={canvasRef}
            className="auth-visual-canvas"
            onMouseMove={handleMouseMove}
            onMouseLeave={handleMouseLeave}
            onClick={handleClick}
            style={{
                position: 'absolute',
                inset: 0,
                width: '100%',
                height: '100%',
                zIndex: 1,
                pointerEvents: 'auto',
                cursor: 'crosshair',
            }}
        />
    );
}
