/* Backend base URL for the admin panel.
   - Served from Render (same origin): leave "" empty.
   - Served from Vercel (cross-origin): set your public C2 URL, e.g.
       window.SIM1_BACKEND = "https://sim1-c2.onrender.com";
   A runtime override stored in localStorage as "sim1_backend" wins over this file. */
window.SIM1_BACKEND = "";
