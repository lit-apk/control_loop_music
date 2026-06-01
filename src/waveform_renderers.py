"""Waveform drawing strategies for the loop progress bar."""

from __future__ import annotations

from abc import ABC, abstractmethod

from PyQt6.QtCore import Qt
from PyQt6.QtGui import QColor, QPainter, QPen


class WaveformRenderer(ABC):
    @abstractmethod
    def draw(self, painter: QPainter, waveform: list[float], left: int, right: int, center_y: int, track_height: int) -> None:
        """Draw normalized waveform amplitudes into the progress track."""


class VerticalBarsWaveformRenderer(WaveformRenderer):
    def __init__(self, color: QColor | None = None) -> None:
        self.color = color or QColor("#6b7280")

    def draw(self, painter: QPainter, waveform: list[float], left: int, right: int, center_y: int, track_height: int) -> None:
        width = max(1, right - left)
        if not waveform:
            painter.setPen(QPen(QColor("#d4d8df"), 1))
            painter.drawLine(left, center_y, right, center_y)
            return

        half_height = max(1, track_height // 2 - 3)
        painter.setPen(QPen(self.color, 1))
        for x_offset in range(width):
            index = min(len(waveform) - 1, x_offset * len(waveform) // width)
            amplitude = max(0.0, min(1.0, waveform[index]))
            y_delta = max(1, round(amplitude * half_height))
            x = left + x_offset
            painter.drawLine(x, center_y - y_delta, x, center_y + y_delta)


class FilledWaveformRenderer(WaveformRenderer):
    def __init__(self, color: QColor | None = None) -> None:
        self.color = color or QColor(107, 114, 128, 120)

    def draw(self, painter: QPainter, waveform: list[float], left: int, right: int, center_y: int, track_height: int) -> None:
        width = max(1, right - left)
        if not waveform:
            painter.setPen(QPen(QColor("#d4d8df"), 1))
            painter.drawLine(left, center_y, right, center_y)
            return

        half_height = max(1, track_height // 2 - 3)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(self.color)
        for x_offset in range(width):
            index = min(len(waveform) - 1, x_offset * len(waveform) // width)
            amplitude = max(0.0, min(1.0, waveform[index]))
            y_delta = max(1, round(amplitude * half_height))
            x = left + x_offset
            painter.drawRect(x, center_y - y_delta, 1, y_delta * 2)
