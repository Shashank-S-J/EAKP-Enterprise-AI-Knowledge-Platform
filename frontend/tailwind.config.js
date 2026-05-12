/** @type {import('tailwindcss').Config} */
export default {
    content: [
        './index.html',
        './src/**/*.{js,jsx,ts,tsx}',
    ],
    // DashboardPage uses arbitrary-value utilities like md:ml-64, bg-[#15121b],
    // h-[400px], shadow-[0_0_20px_rgba(...)] — JIT supports these out of the box.
    theme: {
        extend: {},
    },
    plugins: [],
    // Avoid Tailwind's preflight clobbering the existing App.css / index.css
    // design system (CSS variables, fonts, scrollbar styles, animations).
    corePlugins: {
        preflight: false,
    },
};