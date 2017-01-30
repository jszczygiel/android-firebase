package com.jszczygiel.foundation.repos;

import com.jszczygiel.foundation.repos.interfaces.BaseModel;
import com.jszczygiel.foundation.repos.interfaces.Repo;

import rx.Observable;

public interface FirebaseRepo<T extends BaseModel> extends Repo<T> {
    Observable<Boolean> setUserId(String userId);

    String getUserId();

    boolean isPublic();

    Observable<T> get(String id, String referenceId);

}
