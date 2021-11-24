///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.googlecode.lanterna:lanterna:3.1.1

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.MouseCaptureMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

public class lanterna {
	public static void main(String[] args) {
		DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory()
				.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE);
		try (Screen screen = terminalFactory.createScreen()) {
			screen.startScreen();

			List<String> timezonesAsStrings = new ArrayList<>(Arrays.asList(TimeZone.getAvailableIDs()));

			final WindowBasedTextGUI textGUI = new MultiWindowTextGUI(screen);
			final Window window = new BasicWindow("My Root Window");
			Panel contentPanel = new Panel().setLayoutManager(new GridLayout(2).setHorizontalSpacing(3))
					.addComponent(new Label("This is a label that spans two columns")
							.setLayoutData(GridLayout.createLayoutData(
									GridLayout.Alignment.BEGINNING, // Horizontal alignment in the grid cell if the cell
																	// is larger than the component's preferred size
									GridLayout.Alignment.BEGINNING, // Vertical alignment in the grid cell if the cell
																	// is larger than the component's preferred size
									true, 	// Give the component extra horizontal space if available
									false,	// Give the component extra vertical space if available
									2,		// Horizontal span
									1)))	// Vertical span
					.addComponent(new Label("Text Box (aligned)"))
					.addComponent(new TextBox().setLayoutData(
							GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.CENTER)))

					.addComponent(new Label("Password Box (right aligned)"))
					.addComponent(new TextBox().setMask('*').setLayoutData(
							GridLayout.createLayoutData(GridLayout.Alignment.END, GridLayout.Alignment.CENTER)))

					.addComponent(new Label("Read-only Combo Box (forced size)"))
					.addComponent(new ComboBox<>(timezonesAsStrings).setReadOnly(true)
							.setPreferredSize(new TerminalSize(20, 1)))

					.addComponent(new Label("Editable Combo Box (filled)"))
					.addComponent(new ComboBox<>("Item #1", "Item #2", "Item #3", "Item #4").setReadOnly(false)
							.setLayoutData(GridLayout.createHorizontallyFilledLayoutData(1)))

					.addComponent(new Label("Button (centered)"))
					.addComponent(new Button("Button",
							() -> MessageDialog.showMessageDialog(textGUI, "MessageBox", "This is a message box",
									MessageDialogButton.OK))
											.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.CENTER,
													GridLayout.Alignment.CENTER)))

					.addComponent(new EmptySpace().setLayoutData(GridLayout.createHorizontallyFilledLayoutData(2)))
					.addComponent(new Separator(Direction.HORIZONTAL)
							.setLayoutData(GridLayout.createHorizontallyFilledLayoutData(2)))
					.addComponent(new Button("Close", window::close)
							.setLayoutData(GridLayout.createHorizontallyEndAlignedLayoutData(2)));

			window.setComponent(contentPanel);
			window.addWindowListener(new WindowListenerAdapter() {
				public void onUnhandledInput(Window basePane, KeyStroke keyStroke, AtomicBoolean hasBeenHandled) {
					if (keyStroke.getKeyType() == KeyType.Escape) {
						window.close();
					}
				}
			});

			textGUI.addWindowAndWait(window);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
