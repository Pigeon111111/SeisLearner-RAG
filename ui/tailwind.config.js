/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      keyframes: {
        'fade-in': {
          '0%': { opacity: '0', transform: 'translateY(10px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'fadeIn': {
          '0%': { opacity: '0', transform: 'translateY(-10px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'slideIn': {
          '0%': { opacity: '0', transform: 'translateX(-10px)' },
          '100%': { opacity: '1', transform: 'translateX(0)' },
        },
        'spin-slow': {
          '0%': { transform: 'rotate(0deg)' },
          '100%': { transform: 'rotate(360deg)' },
        },
        'bling': {
          '0%, 100%': { 
            opacity: '1',
            boxShadow: '0 0 0 rgba(59, 130, 246, 0)',
          },
          '50%': { 
            opacity: '0.85',
            boxShadow: '0 0 8px rgba(59, 130, 246, 0.3)',
          },
        },
        'shimmer': {
          '0%': { 
            opacity: '0.7',
            textShadow: '0 0 4px rgba(59, 130, 246, 0.4)',
          },
          '50%': { 
            opacity: '1',
            textShadow: '0 0 8px rgba(59, 130, 246, 0.8), 0 0 12px rgba(59, 130, 246, 0.5)',
          },
          '100%': { 
            opacity: '0.7',
            textShadow: '0 0 4px rgba(59, 130, 246, 0.4)',
          },
        },
      },
      animation: {
        'fade-in': 'fade-in 0.5s ease-out',
        'fadeIn': 'fadeIn 0.3s ease-out',
        'slideIn': 'slideIn 0.4s ease-out',
        'spin-slow': 'spin-slow 2s linear infinite',
        'bling': 'bling 1.5s ease-in-out infinite',
        'shimmer': 'shimmer 2s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};