import cv2
import numpy as np
import threading
import time
import os
import platform
from dataclasses import dataclass
from enum import Enum, auto

# ==========================================
# 1. Configuration & Assets
# ==========================================

class AssetManager:
    def __init__(self):
        # Absolute path calculation
        self.base_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "Assets")
        self.voices_path = os.path.join(self.base_path, "Voices")
        
        # Load Icons with Debugging
        print(f"[ASSETS] Loading from: {self.base_path}")
        self.icon_lower = self._load_image("Lower.png")
        self.icon_upper = self._load_image("Upper.png")
        
        self.system = platform.system()

    def _load_image(self, name):
        path = os.path.join(self.base_path, name)
        if os.path.exists(path):
            img = cv2.imread(path, cv2.IMREAD_UNCHANGED)
            if img is not None:
                print(f"[ASSETS] Loaded {name} successfully. Dimensions: {img.shape}")
                return img
            else:
                print(f"[ERR] {name} found but failed to load (corrupt?).")
        else:
            print(f"[WARN] Asset not found: {name} at {path}")
        return None

    def play_voice(self, filename):
        self._play_sound_file(os.path.join(self.voices_path, filename))

    def _play_sound_file(self, path):
        if not os.path.exists(path): return
        if self.system == "Windows":
            import winsound
            winsound.PlaySound(path, winsound.SND_ASYNC | winsound.SND_FILENAME)
        elif self.system == "Darwin": # macOS
            os.system(f"afplay '{path}' &")
        elif self.system == "Linux":
            os.system(f"aplay '{path}' &")

# ==========================================
# 2. Data Structures
# ==========================================

@dataclass
class MotionState:
    mu: float = 0.0
    sigma: float = 0.0
    speed_warning: bool = False
    stability_warning: bool = False

@dataclass
class GuidanceResult:
    prompt: str
    color: tuple
    motion: MotionState

class ScanningState(Enum):
    READY_TO_SCAN_LOWER = auto()
    SCANNING_LOWER = auto()
    READY_TO_SCAN_UPPER = auto()
    SCANNING_UPPER = auto()
    COMPLETE = auto()

# ==========================================
# 3. Logic: Hysteresis & Guidance
# ==========================================

class HysteresisState:
    def __init__(self, enter_threshold, clear_threshold, k_confirm=5):
        self._enter_threshold = enter_threshold
        self._clear_threshold = clear_threshold
        self._k_confirm = k_confirm
        self._enter_counter = 0
        self._clear_counter = 0
        self.is_warning = False

    def update(self, value):
        if not self.is_warning:
            if value >= self._enter_threshold: self._enter_counter += 1
            else: self._enter_counter = 0
            if self._enter_counter >= self._k_confirm:
                self.is_warning = True
                self._enter_counter = 0
        else:
            if value <= self._clear_threshold: self._clear_counter += 1
            else: self._clear_counter = 0
            if self._clear_counter >= self._k_confirm:
                self.is_warning = False
                self._clear_counter = 0

class GuidanceSystem:
    def __init__(self, asset_manager):
        self.assets = asset_manager
        
        # --- Parameters ---
        self.MOTION_TARGET_WIDTH = 480
        self.LK_EVERY_N = 3
        self.CAPTURE_SPEED_THRESH = 4.0
        self.CAPTURE_STAB_THRESH = 3.0
        self.CAPTURE_DELAY_S = 0.5
        self.CAPTURE_COOLDOWN_S = 1.5
        
        # --- State ---
        self._stop_event = threading.Event()
        self._frame_lock = threading.Lock()
        self._latest_frame = None
        self._new_frame_event = threading.Event()
        self._motion_state_lock = threading.Lock()
        self._motion_state = MotionState()
        self._motion_updated_event = threading.Event()
        self._is_processing_active = False
        
        self.on_guidance_updated = None
        self.on_capture_triggered = None

    def start(self):
        if not self._stop_event.is_set():
            self._stop_event.clear()
            threading.Thread(target=self._motion_worker, daemon=True).start()
            threading.Thread(target=self._state_worker, daemon=True).start()

    def stop(self):
        self._stop_event.set()
        self._new_frame_event.set()
        self._motion_updated_event.set()

    def set_processing_active(self, is_active):
        self._is_processing_active = is_active

    def process_frame(self, frame):
        if self._stop_event.is_set(): return
        with self._frame_lock:
            self._latest_frame = frame.copy()
        self._new_frame_event.set()

    def get_latest_frame(self):
        with self._frame_lock:
            return self._latest_frame.copy() if self._latest_frame is not None else None

    def _motion_worker(self):
        MAX_CORNERS, MIN_CORNERS = 100, 40
        prev_gray, prev_pts = None, None
        mu_smooth, sigma_smooth = None, None
        last_mu_raw, last_sigma_raw = 0, 0
        iteration = 0
        sp_state = HysteresisState(15.0, 12.0)
        sb_state = HysteresisState(10.0, 8.0)

        while not self._stop_event.is_set():
            self._new_frame_event.wait()
            self._new_frame_event.clear()
            if self._stop_event.is_set(): break
            
            with self._frame_lock:
                if self._latest_frame is None: continue
                frame = self._latest_frame.copy()

            # ROI & Resize
            h, w = frame.shape[:2]
            crop = frame[int(h*0.2):int(h*0.8), int(w*0.15):int(w*0.85)]
            scale = self.MOTION_TARGET_WIDTH / crop.shape[1]
            ds = cv2.resize(crop, (self.MOTION_TARGET_WIDTH, int(crop.shape[0]*scale)), interpolation=cv2.INTER_NEAREST)
            gray = cv2.cvtColor(ds, cv2.COLOR_BGR2GRAY)

            # Optical Flow Logic
            mu_raw, sigma_raw = 0, 0
            if prev_gray is None or prev_pts is None or len(prev_pts) < MIN_CORNERS:
                prev_pts = cv2.goodFeaturesToTrack(gray, MAX_CORNERS, 0.01, 8, blockSize=3)
            else:
                if iteration % self.LK_EVERY_N == 0:
                    next_pts, status, _ = cv2.calcOpticalFlowPyrLK(prev_gray, gray, prev_pts, None, winSize=(15,15), maxLevel=2)
                    if next_pts is not None:
                        good_new = next_pts[status == 1]
                        good_old = prev_pts[status == 1]
                        if len(good_new) >= MIN_CORNERS:
                            dists = np.sqrt(np.sum((good_new - good_old)**2, axis=1))
                            mu_raw = np.mean(dists)
                            sigma_raw = np.std(dists) if len(dists) > 1 else 0
                            prev_pts = good_new.reshape(-1, 1, 2)
                        else:
                            prev_pts = None # Reset
                else:
                    mu_raw, sigma_raw = last_mu_raw, last_sigma_raw

            prev_gray = gray
            last_mu_raw, last_sigma_raw = mu_raw, sigma_raw

            # Smoothing
            if mu_smooth is None: mu_smooth, sigma_smooth = mu_raw, sigma_raw
            else:
                mu_smooth = 0.8 * mu_smooth + 0.2 * mu_raw
                sigma_smooth = 0.8 * sigma_smooth + 0.2 * sigma_raw

            sp_state.update(mu_smooth)
            sb_state.update(sigma_smooth)
            
            with self._motion_state_lock:
                self._motion_state = MotionState(mu_smooth, sigma_smooth, sp_state.is_warning, sb_state.is_warning)
            self._motion_updated_event.set()
            iteration += 1

    def _state_worker(self):
        c_green = (60, 200, 60)
        c_amber = (0, 180, 255)
        c_red = (60, 60, 255)
        c_cyan = (255, 200, 80)
        stable_since = None
        last_capture_time = 0

        while not self._stop_event.is_set():
            self._motion_updated_event.wait()
            self._motion_updated_event.clear()
            if self._stop_event.is_set(): break

            with self._motion_state_lock: ms = self._motion_state

            if not self._is_processing_active:
                if self.on_guidance_updated:
                    self.on_guidance_updated(None)
                continue

            # Capture Logic
            is_stable = (ms.mu < self.CAPTURE_SPEED_THRESH) and (ms.sigma < self.CAPTURE_STAB_THRESH)
            is_arming = False
            now = time.time()

            if is_stable:
                if stable_since is None: stable_since = now
                if (now - stable_since) >= self.CAPTURE_DELAY_S:
                    is_arming = True
                    if (now - last_capture_time) >= self.CAPTURE_COOLDOWN_S:
                        if self.on_capture_triggered: self.on_capture_triggered()
                        last_capture_time = now
                        stable_since = None
                        is_arming = False
            else:
                stable_since = None
                is_arming = False

            if ms.speed_warning and ms.stability_warning: prompt, color = "Slow down & keep steady", c_red
            elif ms.speed_warning: prompt, color = "Slow down", c_amber
            elif ms.stability_warning: prompt, color = "Keep steady", c_amber
            elif is_arming: prompt, color = "Hold steady to capture...", c_cyan
            else: prompt, color = "Ready to capture", c_green

            if self.on_guidance_updated:
                self.on_guidance_updated(GuidanceResult(prompt, color, ms))

# ==========================================
# 4. UI Drawing
# ==========================================

class OverlayDrawer:
    def __init__(self, asset_manager):
        self.assets = asset_manager
        self.btn_main_rect = None
        self.btn_recapture_rect = None
        self.flash_frames = 0

    def trigger_flash(self):
        self.flash_frames = 8 

    def draw_ui(self, frame, session_state, guidance_result, ui_text_main, ui_btn_text, progress_text):
        h, w = frame.shape[:2]

        # 1. Status Bar (Only if ACTIVE scanning)
        if guidance_result is not None:
            bar_h = 50
            bar_color = guidance_result.color
            cv2.rectangle(frame, (0, h - bar_h), (w, h), bar_color, -1)
            status_text = guidance_result.prompt
            cv2.putText(frame, status_text, (30, h - 15), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
            
            # Target Box
            cx, cy = w // 2, h // 2
            box_sz = 300
            cv2.rectangle(frame, (cx-box_sz//2, cy-box_sz//2), (cx+box_sz//2, cy+box_sz//2), (255, 255, 100), 2)

        # 2. Top Instruction
        cv2.putText(frame, ui_text_main, (60, 80), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0,0,0), 4)
        cv2.putText(frame, ui_text_main, (60, 80), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255,255,255), 2)

        # 3. Control Panel (Bottom Left)
        panel_w, panel_h = 300, 260
        pad_x, pad_y = 50, 80 
        panel_x, panel_y = pad_x, h - panel_h - pad_y
        
        # Dark Card Background
        overlay = frame.copy()
        cv2.rectangle(overlay, (panel_x, panel_y), (panel_x + panel_w, panel_y + panel_h), (30, 30, 35), -1)
        cv2.addWeighted(overlay, 0.9, frame, 0.1, 0, frame)

        # Arch Icon Logic (Updated)
        icon = None
        if "LOWER" in session_state.name: 
            icon = self.assets.icon_lower
        elif "UPPER" in session_state.name: 
            icon = self.assets.icon_upper
        
        if icon is not None:
            icon_sz = 100
            ix = panel_x + (panel_w - icon_sz) // 2
            iy = panel_y + 30
            # Resize
            try:
                # Maintain aspect ratio
                ratio = icon.shape[1] / icon.shape[0]
                new_w = int(icon_sz * ratio)
                icon_resized = cv2.resize(icon, (new_w, icon_sz))
                
                # Re-calculate center with new width
                ix = panel_x + (panel_w - new_w) // 2
                
                # Draw
                self._overlay_image(frame, icon_resized, ix, iy)
            except Exception as e:
                print(f"[DRAW ERR] Icon draw failed: {e}")

        # "1/2" Text
        text_sz = cv2.getTextSize(progress_text, cv2.FONT_HERSHEY_SIMPLEX, 1.0, 2)[0]
        tx = panel_x + (panel_w - text_sz[0]) // 2
        ty = panel_y + 160
        cv2.putText(frame, progress_text, (tx, ty), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)

        # MAIN ACTION BUTTON
        btn_h = 50
        btn_y = panel_y + 180
        btn_x = panel_x + 20
        btn_w = panel_w - 40
        
        btn_color = (230, 80, 80)
        cv2.rectangle(frame, (btn_x, btn_y), (btn_x + btn_w, btn_y + btn_h), btn_color, -1)
        
        b_sz = cv2.getTextSize(ui_btn_text, cv2.FONT_HERSHEY_SIMPLEX, 0.7, 2)[0]
        bx = btn_x + (btn_w - b_sz[0]) // 2
        by = btn_y + (btn_h + b_sz[1]) // 2
        cv2.putText(frame, ui_btn_text, (bx, by), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
        
        self.btn_main_rect = (btn_x, btn_y, btn_x + btn_w, btn_y + btn_h)

        # RECAPTURE BUTTON
        self.btn_recapture_rect = None 
        show_recapture = session_state in [ScanningState.READY_TO_SCAN_UPPER, ScanningState.COMPLETE]
        
        if show_recapture:
            r_btn_h = 40
            r_btn_y = btn_y + btn_h + 15
            
            # Draw Outline
            cv2.rectangle(frame, (btn_x, r_btn_y), (btn_x + btn_w, r_btn_y + r_btn_h), (50,50,50), -1)
            cv2.rectangle(frame, (btn_x, r_btn_y), (btn_x + btn_w, r_btn_y + r_btn_h), (150,150,150), 1)
            
            r_text = "Recapture"
            r_sz = cv2.getTextSize(r_text, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 1)[0]
            rx = btn_x + (btn_w - r_sz[0]) // 2
            ry = r_btn_y + (r_btn_h + r_sz[1]) // 2
            cv2.putText(frame, r_text, (rx, ry), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (200, 200, 200), 1)
            
            self.btn_recapture_rect = (btn_x, r_btn_y, btn_x + btn_w, r_btn_y + r_btn_h)

        # 4. Handle Capture Flash
        if self.flash_frames > 0:
            self._draw_corner_flash(frame)
            self.flash_frames -= 1

    def _draw_corner_flash(self, frame):
        h, w = frame.shape[:2]
        overlay = frame.copy()
        r = 150 
        cv2.circle(overlay, (0, 0), r, (255, 255, 255), -1)
        cv2.circle(overlay, (w, 0), r, (255, 255, 255), -1)
        cv2.circle(overlay, (0, h), r, (255, 255, 255), -1)
        cv2.circle(overlay, (w, h), r, (255, 255, 255), -1)
        cv2.addWeighted(overlay, 0.4, frame, 0.6, 0, frame)

    def _overlay_image(self, bg, fg, x, y):
        # FIX: Clipping logic added to ensure icons render even if slightly off-screen
        h_fg, w_fg = fg.shape[:2]
        h_bg, w_bg = bg.shape[:2]

        if x >= w_bg or y >= h_bg: return # Totally out of bounds
        
        # Calculate clipping
        x_start = max(0, x)
        y_start = max(0, y)
        x_end = min(w_bg, x + w_fg)
        y_end = min(h_bg, y + h_fg)
        
        # Calculate overlap in foreground terms
        fg_x_start = x_start - x
        fg_y_start = y_start - y
        fg_x_end = fg_x_start + (x_end - x_start)
        fg_y_end = fg_y_start + (y_end - y_start)

        if (x_end - x_start) <= 0 or (y_end - y_start) <= 0: return

        # Case 1: 4 Channels (Transparent PNG)
        if fg.shape[2] == 4:
            fg_crop = fg[fg_y_start:fg_y_end, fg_x_start:fg_x_end]
            bg_crop = bg[y_start:y_end, x_start:x_end]
            
            alpha_fg = fg_crop[:, :, 3] / 255.0
            alpha_bg = 1.0 - alpha_fg
            
            for c in range(3):
                bg_crop[:, :, c] = (alpha_fg * fg_crop[:,:,c] + alpha_bg * bg_crop[:,:,c])
                
            bg[y_start:y_end, x_start:x_end] = bg_crop

        # Case 2: 3 Channels (Standard JPG/PNG without alpha)
        elif fg.shape[2] == 3:
            bg[y_start:y_end, x_start:x_end] = fg[fg_y_start:fg_y_end, fg_x_start:fg_x_end]

# ==========================================
# 5. Session Manager
# ==========================================

class SessionManager:
    def __init__(self, guidance_system, asset_manager, drawer):
        self.guidance = guidance_system
        self.assets = asset_manager
        self.drawer = drawer
        self.state = ScanningState.READY_TO_SCAN_LOWER
        self.main_text = ""
        self.btn_text = ""
        self.progress_text = ""
        
        self.save_dir = os.path.join(os.getcwd(), "Captures")
        if not os.path.exists(self.save_dir): os.makedirs(self.save_dir)
        
        # File Tracking for Deletion
        self.files_lower = []
        self.files_upper = []
        
        self.guidance.on_capture_triggered = self.on_internal_capture

    def start_session(self):
        self.guidance.start()
        self.state = ScanningState.READY_TO_SCAN_LOWER
        self.update_ui_state()

    def action_button_click(self):
        if self.state == ScanningState.READY_TO_SCAN_LOWER:
            self.state = ScanningState.SCANNING_LOWER
            self.guidance.set_processing_active(True)
        elif self.state == ScanningState.SCANNING_LOWER:
            self.state = ScanningState.READY_TO_SCAN_UPPER
            self.guidance.set_processing_active(False)
        elif self.state == ScanningState.READY_TO_SCAN_UPPER:
            self.state = ScanningState.SCANNING_UPPER
            self.guidance.set_processing_active(True)
        elif self.state == ScanningState.SCANNING_UPPER:
            self.state = ScanningState.COMPLETE
            self.guidance.set_processing_active(False)
        elif self.state == ScanningState.COMPLETE:
            # Full Reset
            self.files_lower = []
            self.files_upper = []
            self.state = ScanningState.READY_TO_SCAN_LOWER
            
        self.update_ui_state()

    def recapture_click(self):
        # --- FIX: RECAPTURE LOGIC UPDATE ---
        
        # Scenario 1: User just finished Lower scan, hasn't started Upper.
        # Action: Undo Lower scan.
        if self.state == ScanningState.READY_TO_SCAN_UPPER:
            print("[SESSION] Recapturing Lower Arch... Deleting lower files.")
            self._delete_files(self.files_lower)
            self.files_lower = []
            self.state = ScanningState.READY_TO_SCAN_LOWER
            self.guidance.set_processing_active(False)
            
        # Scenario 2: User finished Upper scan (Session Complete).
        # Action: Undo Upper scan only. Go back to start of Upper.
        elif self.state == ScanningState.COMPLETE:
            print("[SESSION] Recapturing Upper Arch... Deleting upper files.")
            self._delete_files(self.files_upper)
            self.files_upper = []
            self.state = ScanningState.READY_TO_SCAN_UPPER  # Go back to start of Upper
            self.guidance.set_processing_active(False)
            
        self.update_ui_state()

    def _delete_files(self, file_list):
        for fpath in file_list:
            try:
                if os.path.exists(fpath):
                    os.remove(fpath)
                    print(f"[DISK] Deleted {os.path.basename(fpath)}")
            except Exception as e:
                print(f"[ERR] Could not delete {fpath}: {e}")

    def on_internal_capture(self):
        self.drawer.trigger_flash()
        
        frame = self.guidance.get_latest_frame()
        if frame is None: return
        ts = int(time.time() * 1000)
        
        # Determine Arch and save to correct list
        arch = ""
        if self.state == ScanningState.SCANNING_LOWER:
            arch = "LOWER"
        else:
            arch = "UPPER"
            
        filename = f"{arch}_{ts}.jpg"
        full_path = os.path.join(self.save_dir, filename)
        
        cv2.imwrite(full_path, frame)
        print(f"[DISK] Saved {filename}")
        
        # Add to tracking list
        if arch == "LOWER":
            self.files_lower.append(full_path)
        else:
            self.files_upper.append(full_path)

    def update_ui_state(self):
        if self.state == ScanningState.READY_TO_SCAN_LOWER:
            self.main_text = "Place scanner at LEFT of LOWER arch."
            self.btn_text = "Start Lower Scan"
            self.progress_text = "1/2"
            self.assets.play_voice("Ins1.wav")
            
        elif self.state == ScanningState.SCANNING_LOWER:
            self.main_text = "Move along LOWER arch to the right."
            self.btn_text = "Finish Lower Scan"
            self.progress_text = "1/2"
            self.assets.play_voice("Ins2.wav")
            
        elif self.state == ScanningState.READY_TO_SCAN_UPPER:
            self.main_text = "Place scanner at LEFT of UPPER arch."
            self.btn_text = "Start Upper Scan"
            self.progress_text = "2/2"
            self.assets.play_voice("Ins3.wav")
            
        elif self.state == ScanningState.SCANNING_UPPER:
            self.main_text = "Move along UPPER arch to the right."
            self.btn_text = "Finish Upper Scan"
            self.progress_text = "2/2"
            self.assets.play_voice("Ins4.wav")
            
        elif self.state == ScanningState.COMPLETE:
            self.main_text = "Scan Complete!"
            self.btn_text = "Finish Session"
            self.progress_text = "Done"
            self.assets.play_voice("Ins5.wav")

# ==========================================
# 6. Main Entry Point
# ==========================================

g_session = None
g_drawer = None

def mouse_callback(event, x, y, flags, param):
    if event == cv2.EVENT_LBUTTONDOWN:
        if g_drawer and g_session:
            # Main Button
            r1 = g_drawer.btn_main_rect
            if r1 and r1[0] <= x <= r1[2] and r1[1] <= y <= r1[3]:
                g_session.action_button_click()
                return
            # Recapture Button
            r2 = g_drawer.btn_recapture_rect
            if r2 and r2[0] <= x <= r2[2] and r2[1] <= y <= r2[3]:
                g_session.recapture_click()

def main():
    global g_session, g_drawer
    
    cap = cv2.VideoCapture(0)
    # High Res attempt
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1920)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 1080)
    
    if not cap.isOpened():
        print("Error: No camera.")
        return

    assets = AssetManager()
    drawer = OverlayDrawer(assets)
    guidance = GuidanceSystem(assets)
    g_session = SessionManager(guidance, assets, drawer)
    g_drawer = drawer
    
    latest_result = None
    def on_update(res): nonlocal latest_result; latest_result = res
    guidance.on_guidance_updated = on_update
    
    g_session.start_session()
    
    window_name = "Dental Scanner"
    cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)
    cv2.setWindowProperty(window_name, cv2.WND_PROP_FULLSCREEN, cv2.WINDOW_FULLSCREEN)
    cv2.setMouseCallback(window_name, mouse_callback)

    while True:
        ret, frame = cap.read()
        if not ret: break

        # Mirror view
        frame = cv2.flip(frame, 1)

        guidance.process_frame(frame)

        display_frame = frame.copy()
        g_drawer.draw_ui(display_frame, g_session.state, latest_result, 
                         g_session.main_text, g_session.btn_text, g_session.progress_text)
        
        cv2.imshow(window_name, display_frame)

        key = cv2.waitKey(1) & 0xFF
        if key == ord('q'): break
        elif key == ord(' '): g_session.action_button_click()
        elif key == ord('r'): g_session.recapture_click()

    guidance.stop()
    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()