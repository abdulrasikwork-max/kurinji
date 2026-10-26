// V25: the e-mail verification page. The token travels in the URL FRAGMENT (#t=...), which browsers never send to
// any server, so it cannot end up in an access log, a proxy log or a Referer header. It is read once, removed from the
// address bar, and posted to the API only when the person taps the button (so a link scanner that merely opens the
// page cannot use the link up). The server decides; this page only shows its answer.
(function () {
  'use strict';
  var match = /(?:^#|&)t=([A-Za-z0-9_-]{43})(?:&|$)/.exec(window.location.hash || '');
  var token = match ? match[1] : null;
  if (window.history && window.history.replaceState) {
    window.history.replaceState(null, '', window.location.pathname);
  }

  var title = document.getElementById('title');
  var message = document.getElementById('message');
  var note = document.getElementById('note');
  var button = document.getElementById('confirm');

  function show(heading, text, small) {
    title.textContent = heading;
    message.textContent = text;
    note.textContent = small;
    button.hidden = true;
  }

  function unusable() {
    show('This link can’t be used',
      'It may have expired, been replaced by a newer email, or already been used.',
      'If you already confirmed your email, just sign in. Otherwise open Kurinji, try to sign in and ask for a new email.');
  }

  if (!token) {
    unusable();
    return;
  }

  button.addEventListener('click', function () {
    button.disabled = true;
    button.textContent = 'Confirming…';
    fetch('/api/auth/verify-email', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: token }),
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer'
    }).then(function (response) {
      token = null;
      if (response.ok) {
        show('Your email is confirmed',
          'Go back to the Kurinji app and sign in with your email and password.',
          'You can close this page.');
      } else if (response.status === 429) {
        show('Too many attempts', 'Please wait a few minutes and open the link again.', '');
      } else {
        unusable();
      }
    }).catch(function () {
      button.disabled = false;
      button.textContent = 'Try again';
      message.textContent = 'Could not reach Kurinji. Check your connection and try again.';
    });
  });
})();
