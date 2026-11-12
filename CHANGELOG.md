# Changelog

All notable changes to Nimbus are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Nimbus uses
[semantic versioning](https://semver.org/).

## [Unreleased]

## [0.3.2] - 2026-11-12

### Fixed

- TV guide: channels whose guide id differs from the guide feed's only in capitals (`ITV1.uk` / `itv1.uk`) now
  get their programmes, also when the feed lists both spellings and only one has any.
- The playlist and guide are checked for updates every time Nimbus comes to the front and every half hour
  while it's open, not only when the app starts, and an update that failed is tried again.
- Channels that get their guide one by one are asked again after a failed request and after each guide
  update, instead of staying empty until Nimbus restarts.

## [0.3.1] - 2026-11-02

### Changed

- The ☰ (menu) button opens the options for whatever is selected: on Home, in the film and series grids and in
  the TV guide, where channel groups are now among those options. Holding OK still works on remotes without ☰.
- Live TV no longer keeps a "behind live" badge on screen while you watch a paused or rewound channel. ⏪ and
  ⏩ still show where playback will jump to while you press them.

## [0.3.0] - 2026-11-02

First public release.

### Live TV

- Full programme guide that scrolls days into the past and future, with channel groups, favourites,
  reminders and a mini TV that keeps playing while you browse.
- Pause and rewind any channel: live TV records into a memory buffer while you watch, so pauses resume
  exactly where you left off and short network drops never reach the screen.
- Every start, rewind and "back to live" begins on a complete picture, so the picture moves straight away
  even on channels that send a full frame only every few seconds.

### Catch-up

- Catch-up and "watch from start" on channels with an archive (Xtream Codes timeshift and M3U catch-up
  templates).
- One continuous timeline: programmes play straight on into the next, and rewind / fast-forward scrub
  across programme boundaries through the whole archive, up to live.
- The archive is fetched ahead into memory and dropped connections are spliced back in seamlessly;
  seeking within what's already loaded is instant.

### Films and series

- Categories, posters, details, seasons and episodes, resume points, Continue watching (hold OK to remove a
  title) and My list.
- Ratings, reviews, cast and trailers with an optional TMDB API key.
- A version picker when a provider lists the same title several times.

### Everywhere

- Fast search across channels, what's on, films and series, with an on-screen keyboard beside the results.
- Frame-rate matching (50 Hz for European TV, 23.976 Hz for films), switched before playback starts.
- Xtream Codes and M3U playlists, with XMLTV guides; set up from your phone by scanning a QR code.
- Parental PIN, adult group locking and hidden channel groups.
- Optional household hub for syncing playlists, favourites and resume points between TVs, and for updates.
