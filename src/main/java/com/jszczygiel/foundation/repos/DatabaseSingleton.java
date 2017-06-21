package com.jszczygiel.foundation.repos;

import com.google.firebase.database.FirebaseDatabase;

public class DatabaseSingleton {

  private static FirebaseDatabase INSTANCE;

  public static synchronized FirebaseDatabase getInstance() {
    if (INSTANCE == null) {
      INSTANCE = FirebaseDatabase.getInstance();
      INSTANCE.setPersistenceEnabled(true);
    }
    return INSTANCE;
  }
}
