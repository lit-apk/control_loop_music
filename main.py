#!/usr/bin/env python3
"""Loop a music file with a draggable loop-range control."""

from __future__ import annotations

import sys
from pathlib import Path

from PyQt6.QtCore import QUrl, Qt, pyqtSignal
from PyQt6.QtGui import QColor, QFontMetrics, QPainter, QPen
from PyQt6.QtMultimedia import QAudioOutput, QMediaPlayer
from PyQt6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QSizePolicy,
    QSlider,
    QVBoxLayout,
    QWidget,
)


def format_ms(milliseconds: int) -> str:
    seconds = max(0, milliseconds // 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours:d}:{minutes:02d}:{seconds:02d}"
    return f"{minutes:d}:{seconds:02d}"


def format_seconds(milliseconds: int) -> str:
    return f"{max(0, milliseconds / 1000):.2f}s"


class LoopRangeBar(QWidget):
    """A full-width draggable start/end range selector."""

    rangeChanged = pyqtSignal(int, int)
    positionChangedRequested = pyqtSignal(int)

    HANDLE_RADIUS = 10
    TRACK_HEIGHT = 8
    MIN_GAP_MS = 250

    def __init__(self) -> None:
        super().__init__()
        self._duration = 0
        self._start = 0
        self._end = 0
        self._position = 0
        self._dragging: str | None = None
        self.setMinimumHeight(72)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.setMouseTracking(True)

    def set_duration(self, duration: int) -> None:
        self._duration = max(0, duration)
        self._start = 0
        self._end = self._duration
        self.update()
        self.rangeChanged.emit(self._start, self._end)

    def set_position(self, position: int) -> None:
        self._position = max(0, min(position, self._duration))
        self.update()

    def range(self) -> tuple[int, int]:
        return self._start, self._end

    def paintEvent(self, event) -> None:  # noqa: N802
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        left, right = self._track_bounds()
        center_y = self.height() // 2
        track_y = center_y - self.TRACK_HEIGHT // 2

        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor("#d4d8df"))
        painter.drawRoundedRect(left, track_y, right - left, self.TRACK_HEIGHT, 4, 4)

        start_x = self._ms_to_x(self._start)
        end_x = self._ms_to_x(self._end)
        painter.setBrush(QColor("#2f80ed"))
        painter.drawRoundedRect(start_x, track_y, end_x - start_x, self.TRACK_HEIGHT, 4, 4)

        painter.setBrush(QColor("#ffffff"))
        painter.setPen(QPen(QColor("#1d4f91"), 2))
        painter.drawEllipse(start_x - self.HANDLE_RADIUS, center_y - self.HANDLE_RADIUS, self.HANDLE_RADIUS * 2, self.HANDLE_RADIUS * 2)
        painter.drawEllipse(end_x - self.HANDLE_RADIUS, center_y - self.HANDLE_RADIUS, self.HANDLE_RADIUS * 2, self.HANDLE_RADIUS * 2)

        position_x = self._ms_to_x(self._position)
        line_top = center_y - self.HANDLE_RADIUS - 2
        line_bottom = center_y + self.HANDLE_RADIUS + 2
        painter.setPen(QPen(QColor("#d62828"), 3))
        painter.drawLine(position_x, line_top, position_x, line_bottom)

        painter.setPen(QColor("#1f2937"))
        self._draw_handle_label(painter, start_x, center_y - self.HANDLE_RADIUS - 8, format_seconds(self._start))
        self._draw_handle_label(painter, end_x, center_y - self.HANDLE_RADIUS - 8, format_seconds(self._end))

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() != Qt.MouseButton.LeftButton or self._duration <= 0:
            return

        x = int(event.position().x())
        handle_distances = {
            "start": abs(x - self._ms_to_x(self._start)),
            "end": abs(x - self._ms_to_x(self._end)),
            "position": abs(x - self._ms_to_x(self._position)),
        }
        self._dragging = min(handle_distances, key=handle_distances.get)
        self._move_handle(x)

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if self._dragging:
            self._move_handle(int(event.position().x()))
            return

        x = int(event.position().x())
        near_handle = min(
            abs(x - self._ms_to_x(self._start)),
            abs(x - self._ms_to_x(self._end)),
            abs(x - self._ms_to_x(self._position)),
        ) <= self.HANDLE_RADIUS + 4
        self.setCursor(Qt.CursorShape.SizeHorCursor if near_handle else Qt.CursorShape.ArrowCursor)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        del event
        self._dragging = None

    def _move_handle(self, x: int) -> None:
        value = self._x_to_ms(x)
        min_gap = min(self.MIN_GAP_MS, self._duration)

        if self._dragging == "start":
            self._start = max(0, min(value, self._end - min_gap))
            self._position = max(self._start, self._position)
            self.rangeChanged.emit(self._start, self._end)
        elif self._dragging == "end":
            self._end = min(self._duration, max(value, self._start + min_gap))
            self._position = min(self._position, self._end)
            self.rangeChanged.emit(self._start, self._end)
        elif self._dragging == "position":
            self._position = max(self._start, min(value, self._end))
            self.positionChangedRequested.emit(self._position)

        self.update()

    def _track_bounds(self) -> tuple[int, int]:
        pad = self.HANDLE_RADIUS + 4
        return pad, max(pad + 1, self.width() - pad)

    def _ms_to_x(self, value: int) -> int:
        left, right = self._track_bounds()
        if self._duration <= 0:
            return left
        return left + round((right - left) * value / self._duration)

    def _x_to_ms(self, x: int) -> int:
        left, right = self._track_bounds()
        x = max(left, min(right, x))
        if self._duration <= 0:
            return 0
        return round(self._duration * (x - left) / (right - left))

    def _draw_handle_label(self, painter: QPainter, center_x: int, baseline_y: int, text: str) -> None:
        metrics = QFontMetrics(painter.font())
        text_width = metrics.horizontalAdvance(text)
        x = max(0, min(self.width() - text_width, center_x - text_width // 2))
        painter.drawText(x, baseline_y, text)


class LoopPlayerWindow(QMainWindow):
    def __init__(self, music_path: Path) -> None:
        super().__init__()
        self.music_path = music_path
        self.setWindowTitle(f"Loop Player - {music_path.name}")
        self.resize(760, 220)

        self.audio_output = QAudioOutput()
        self.audio_output.setVolume(0.8)

        self.player = QMediaPlayer()
        self.player.setAudioOutput(self.audio_output)
        self.player.setSource(QUrl.fromLocalFile(str(music_path)))

        self.loop_bar = LoopRangeBar()
        self.play_button = QPushButton("Pause")
        self.position_label = QLabel("0:00 / 0:00")
        self.range_label = QLabel("Loop: 0:00 - 0:00")
        self.status_label = QLabel(str(music_path))
        self.volume_slider = QSlider(Qt.Orientation.Horizontal)

        self._build_ui()
        self._connect_signals()

        self.player.play()

    def _build_ui(self) -> None:
        self.status_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.status_label.setWordWrap(True)

        self.volume_slider.setRange(0, 100)
        self.volume_slider.setValue(80)
        self.volume_slider.setFixedWidth(150)

        controls = QHBoxLayout()
        controls.addWidget(self.play_button)
        controls.addWidget(self.position_label)
        controls.addStretch(1)
        controls.addWidget(QLabel("Volume"))
        controls.addWidget(self.volume_slider)

        layout = QVBoxLayout()
        layout.addWidget(self.status_label)
        layout.addWidget(self.loop_bar)
        layout.addWidget(self.range_label)
        layout.addLayout(controls)

        central = QWidget()
        central.setLayout(layout)
        self.setCentralWidget(central)

    def _connect_signals(self) -> None:
        self.play_button.clicked.connect(self._toggle_playback)
        self.volume_slider.valueChanged.connect(lambda value: self.audio_output.setVolume(value / 100))
        self.loop_bar.rangeChanged.connect(self._on_loop_range_changed)
        self.loop_bar.positionChangedRequested.connect(self._seek_to)

        self.player.durationChanged.connect(self._on_duration_changed)
        self.player.positionChanged.connect(self._on_position_changed)
        self.player.errorOccurred.connect(self._on_error)
        self.player.mediaStatusChanged.connect(self._on_media_status_changed)

    def _toggle_playback(self) -> None:
        if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.player.pause()
            self.play_button.setText("Play")
        else:
            start, end = self.loop_bar.range()
            if end > start and not (start <= self.player.position() <= end):
                self.player.setPosition(start)
            self.player.play()
            self.play_button.setText("Pause")

    def _on_duration_changed(self, duration: int) -> None:
        self.loop_bar.set_duration(duration)
        self._update_labels(self.player.position())

    def _on_position_changed(self, position: int) -> None:
        start, end = self.loop_bar.range()
        if end > start and position >= end:
            self.player.setPosition(start)
            return
        self.loop_bar.set_position(position)
        self._update_labels(position)

    def _on_loop_range_changed(self, start: int, end: int) -> None:
        if self.player.position() < start or self.player.position() > end:
            self.player.setPosition(start)
            self.loop_bar.set_position(start)
        self._update_labels(self.player.position())

    def _seek_to(self, position: int) -> None:
        self.player.setPosition(position)
        self.loop_bar.set_position(position)
        self._update_labels(position)

    def _on_media_status_changed(self, status: QMediaPlayer.MediaStatus) -> None:
        if status == QMediaPlayer.MediaStatus.EndOfMedia:
            start, _end = self.loop_bar.range()
            self.player.setPosition(start)
            self.player.play()

    def _on_error(self, error: QMediaPlayer.Error, message: str) -> None:
        if error == QMediaPlayer.Error.NoError:
            return
        QMessageBox.critical(self, "Playback error", message or "Could not play this file.")

    def _update_labels(self, position: int) -> None:
        start, end = self.loop_bar.range()
        duration = self.player.duration()
        self.position_label.setText(f"{format_ms(position)} / {format_ms(duration)}")
        self.range_label.setText(f"Loop: {format_ms(start)} - {format_ms(end)}")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"Usage: {Path(argv[0]).name} MUSIC_FILE", file=sys.stderr)
        return 2

    music_path = Path(argv[1]).expanduser().resolve()
    if not music_path.is_file():
        print(f"File not found: {music_path}", file=sys.stderr)
        return 2

    app = QApplication(argv)
    window = LoopPlayerWindow(music_path)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
