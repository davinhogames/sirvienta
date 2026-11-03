const androidDownload = document.getElementById('androidDownload');
const androidMeta = document.getElementById('androidMeta');
const downloadNotice = document.getElementById('downloadNotice');
const publicSite = window.SIRVIENTA_PUBLIC_SITE === true;

function preparePublicIphoneCard() {
  if (!publicSite) return;

  document.querySelector('meta[name="description"]').content =
    'Descarga la beta Android de Sirvienta. La app nativa para iPhone sigue en desarrollo.';
  document.querySelector('meta[property="og:description"]').content =
    'Descarga la beta Android de Sirvienta. La app nativa para iPhone sigue en desarrollo.';
  document.querySelector('.hero-description').textContent =
    'Una asistente de voz que te ayuda a pensar, recordar y poner tus ideas en marcha. '
    + 'La beta está disponible en Android; la app nativa para iPhone sigue en desarrollo.';

  const iphoneCard = document.querySelector('.iphone-card');
  iphoneCard.querySelector('.availability').innerHTML = '<i></i> APP NATIVA EN DESARROLLO';
  iphoneCard.querySelector('.platform-description').textContent =
    'La versión nativa para iPhone todavía no está disponible. Esta página no sustituye una app con escucha en segundo plano.';
  iphoneCard.querySelector('.feature-list').innerHTML = [
    '<li><span>!</span> No hay una app nativa publicable todavía</li>',
    '<li><span>!</span> La web no puede escuchar con la pantalla bloqueada</li>',
    '<li><span>!</span> Requiere Mac, Xcode y pruebas en un iPhone</li>',
  ].join('');
  const iphoneButton = iphoneCard.querySelector('.platform-button');
  iphoneButton.removeAttribute('href');
  iphoneButton.setAttribute('aria-disabled', 'true');
  iphoneButton.classList.add('is-unavailable');
  iphoneButton.textContent = 'App para iPhone en preparación';
  iphoneCard.querySelector('.platform-meta').textContent =
    'No está disponible para instalar ni para App Store.';

  document.querySelector('.platform-caveat').innerHTML =
    '<strong>Nota para iPhone:</strong> la app nativa aún no está disponible. '
    + 'Esta web de descargas no incluye un asistente iPhone ni escucha en segundo plano.';
  document.querySelectorAll('a[href="#iphone"]').forEach((link) => {
    link.removeAttribute('href');
    link.setAttribute('aria-disabled', 'true');
    link.classList.add('is-unavailable');
    link.textContent = 'App para iPhone en preparación';
  });

  const steps = document.querySelectorAll('.step-list .step p');
  steps[0].textContent = 'La beta descargable de Sirvienta está disponible para Android ARM64.';
  steps[1].textContent = 'Android te pedirá confirmar la instalación del APK. La versión iPhone aún no se puede instalar.';
  steps[2].textContent = 'Android solicita los permisos necesarios para sus funciones de voz y recordatorios.';

  const faqAnswers = document.querySelectorAll('#preguntas details p');
  faqAnswers[0].textContent =
    'No. La app nativa para iPhone sigue en desarrollo; esta web solo ofrece la descarga de Android.';
  faqAnswers[1].textContent =
    'Todavía no hay una app iPhone disponible. La web pública es una página de descargas.';
  faqAnswers[2].textContent =
    'La app nativa para iPhone aún no está disponible, así que no se puede usar el micrófono desde esta web.';
  faqAnswers[4].textContent =
    'La beta Android ofrece escucha mediante un servicio visible, sujeta a las restricciones de batería. No hay una app iPhone que escuche en segundo plano.';

  document.querySelector('#privacidad > div:last-child p').textContent =
    'Este sitio solo publica información y descargas. La beta Android procesa varias funciones en el teléfono; '
    + 'no se ofrece aquí un asistente web conectado ni una app iPhone.';
}

function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '';
  return `${(bytes / (1024 ** 3)).toFixed(2)} GB`;
}

async function updateDownloadAvailability() {
  try {
    const manifestUrl = window.SIRVIENTA_RELEASE_MANIFEST_URL;
    const response = await fetch(manifestUrl || '/api/downloads', { cache: 'no-store' });
    if (!response.ok) throw new Error('No se pudo consultar la disponibilidad de la descarga.');
    const release = await response.json();
    const android = manifestUrl ? release : release.android;

    if (!android.available) {
      androidDownload.setAttribute('aria-disabled', 'true');
      androidDownload.removeAttribute('href');
      androidDownload.classList.add('is-unavailable');
      androidDownload.textContent = 'APK temporalmente no disponible';
      androidMeta.textContent = 'La versión Android no está publicada en este servidor.';
      downloadNotice.hidden = false;
      downloadNotice.textContent = 'Vuelve a intentarlo más tarde.';
      return;
    }

    if (android.download_url) {
      androidDownload.href = android.download_url;
    }
    const size = formatFileSize(android.size_bytes);
    const version = android.version_name ? `v${android.version_name} · ` : '';
    const kind = android.kind === 'release' ? 'APK beta firmada' : 'APK beta de desarrollo';
    androidMeta.textContent = `${version}${kind} · ${android.architecture}${size ? ` · ${size}` : ''}`;
    downloadNotice.hidden = true;
  } catch (error) {
    androidMeta.textContent = 'APK beta · Android ARM64';
    downloadNotice.hidden = false;
    downloadNotice.textContent = 'No se pudo comprobar el estado de la descarga. Prueba el botón de nuevo.';
  }
}

preparePublicIphoneCard();
updateDownloadAvailability();
