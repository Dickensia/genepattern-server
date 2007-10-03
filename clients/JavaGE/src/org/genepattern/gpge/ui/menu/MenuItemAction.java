/*
  The Broad Institute
  SOFTWARE COPYRIGHT NOTICE AGREEMENT
  This software and its documentation are copyright (2003-2008) by the
  Broad Institute/Massachusetts Institute of Technology. All rights are
  reserved.

  This software is supplied without any warranty or guaranteed support
  whatsoever. Neither the Broad Institute nor MIT can be responsible for its
  use, misuse, or functionality.
*/


package org.genepattern.gpge.ui.menu;
/*
    Sample code.
    Permission is given to use or modify this code in your own code.
    Lee Ann Rucker
  */
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.EventListenerList;

/**
 *  MenuItemAction is an AbstractAction designed to control JMenuItems which are
 *  shared amongst several JMenuBars. For convenience, it uses ActionListeners
 *  just like JMenuItems do. This implementation uses getMenuShortcutKeyMask to
 *  provide the correct modifiers for the current platform. It's recommended you
 *  not use mnemonics on Macintoshes, as that is counter to the Mac User
 *  Interface
 *
 * @author    Joshua Gould
 */
public class MenuItemAction extends AbstractAction {
   protected EventListenerList listenerList = new EventListenerList();
   static int sMenuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();


   /**
    *  Creates a menuItemAction with text.
    *
    * @param  text  the text of the MenuItem.
    */
   public MenuItemAction(String text) {
      super(text);
   }
   
   /**
    *  Creates a menuItemAction with text.
    *
    * @param  text  the text of the MenuItem.
    */
   public MenuItemAction(String text, Icon icon) {
      super(text, icon);
   }



   /**
    *  Creates a menuItemAction with text and an accelerator.
    *
    * @param  text   the text of the MenuItem.
    * @param  accel  the key which will be combined with the default
    *      MenuShortcutKeyMask to create the accelerator
    * @see           #Toolkit.getMenuShortcutKeyMask
    */
   public MenuItemAction(String text, int accel) {
      this(text, accel, 0);
   }


   /**
    *  Creates a menuItemAction with text and an accelerator.
    *
    * @param  text       the text of the MenuItem.
    * @param  accel      the key which will be combined with the default
    *      MenuShortcutKeyMask to create the accelerator
    * @param  extraMask  the modifiers which will be combined with the default
    *      modifier For best results, extraMask should not be ctrl or meta
    *      because the default might be one of those ctrl-meta shortcuts are not
    *      common on any platform
    * @see               #Toolkit.getMenuShortcutKeyMask
    */

   MenuItemAction(String text, int accel, int extraMask) {
      super(text);
      putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(accel, sMenuMask | extraMask));
   }


   /**
    *  Create a <code>JMenuItem</code> that uses this <code>Action</code>. You
    *  could just create a new JMenuItem directly, but prior to 1.4,
    *  JMenuItem.configurePropertiesFromAction ignores ACCELERATOR_KEY If you
    *  set it after creation, remember that JMenuItem's default
    *  PropertyChangeListener isn't looking for it either, so you'll need to add
    *  your own
    *
    * @return    Description of the Return Value
    */
   public JMenuItem createMenuItem() {
      JMenuItem mi = new JMenuItem(this);
      KeyStroke accel = (KeyStroke) getValue(Action.ACCELERATOR_KEY);
      if(accel != null) {
         mi.setAccelerator(accel);
      }
      return mi;
   }


   /**
    *  Adds an <code>ActionListener</code> to the Action.
    *
    * @param  l  the <code>ActionListener</code> to be added
    */
   public void addActionListener(ActionListener l) {
      listenerList.add(ActionListener.class, l);
   }


   /**
    *  Removes an <code>ActionListener</code> from the Action.
    *
    * @param  l  the listener to be removed
    */
   public void removeActionListener(ActionListener l) {
      listenerList.remove(ActionListener.class, l);
   }


   /**
    *  Notifies all listeners that have registered interest for notification on
    *  this event type.
    *
    * @param  event  Description of the Parameter
    * @see           EventListenerList
    */
   public void actionPerformed(ActionEvent event) {
      // Guaranteed to return a non-null array
      Object[] listeners = listenerList.getListenerList();
      // Process the listeners last to first, notifying
      // those that are interested in this event
      for(int i = listeners.length - 2; i >= 0; i -= 2) {
         if(listeners[i] == ActionListener.class) {
            ((ActionListener) listeners[i + 1]).actionPerformed(event);
         }
      }
   }
}

