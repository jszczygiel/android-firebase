package com.jszczygiel.foundation.repos;

import android.text.TextUtils;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.jszczygiel.foundation.containers.Tuple;
import com.jszczygiel.foundation.enums.SubjectAction;
import com.jszczygiel.foundation.helpers.LoggerHelper;
import com.jszczygiel.foundation.repos.interfaces.BaseModel;
import com.jszczygiel.foundation.repos.interfaces.Repo;
import com.jszczygiel.foundation.rx.PublishSubject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;

public abstract class FirebaseRepoImpl<T extends BaseModel> implements Repo<T> {

    protected final DatabaseReference table;
    private final PublishSubject<Tuple<Integer, T>> subject;
    private final PublishSubject<List<T>> collectionSubject;
    protected String userId;
    private ChildEventListener reference;

    public FirebaseRepoImpl() {
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        table = database.getReference(getTableName());
        collectionSubject = PublishSubject.createWith(PublishSubject.BUFFER);
        subject = PublishSubject.createWith(PublishSubject.BUFFER);
    }

    public abstract String getTableName();

    @Override
    public void setUserId(String userId) {
        this.userId = userId;
        init();
    }

    private void init() {
        if (reference == null) {
            reference = getReference().addChildEventListener(new ChildEventListener() {
                @Override
                public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                    if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.ADDED, dataSnapshot.getValue(getType())));
                    }
                }

                @Override
                public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                    if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.CHANGED, dataSnapshot.getValue(getType())));
                    }
                }

                @Override
                public void onChildRemoved(DataSnapshot dataSnapshot) {
                    if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.REMOVED, dataSnapshot.getValue(getType())));
                    }
                }

                @Override
                public void onChildMoved(DataSnapshot dataSnapshot, String s) {

                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    LoggerHelper.log(databaseError.toException());
                }
            });
        }
    }

    protected DatabaseReference getReference() {
        if (isPublic()) {
            return table;
        } else {
            return table.child(userId);
        }
    }

    public abstract Class<T> getType();

    @Override
    public Observable<T> get(final String id) {
        return Observable.create(new Observable.OnSubscribe<T>() {
            @Override
            public void call(final Subscriber<? super T> subscriber) {
                getReference().child(id).orderByKey().addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        T model = dataSnapshot.getValue(getType());
                        if (model != null) {
                            subscriber.onNext(model);
                        }
                        subscriber.onCompleted();
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        subscriber.onError(databaseError.toException());

                    }
                });
            }
        });
    }

    @Override
    public Observable<T> getAll() {
        return Observable.create(new Observable.OnSubscribe<T>() {
            @Override
            public void call(final Subscriber<? super T> subscriber) {
                getReference().orderByKey().addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                            T model = snapshot.getValue(getType());
                            if (model != null) {
                                subscriber.onNext(model);
                            }
                        }
                        subscriber.onCompleted();
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        subscriber.onError(databaseError.toException());

                    }
                });
            }
        });

    }

    @Override
    public void notify(T model) {
        if (subject.hasObservers()) {
            subject.onNext(new Tuple<>(SubjectAction.CHANGED, model));
        }
    }

    @Override
    public void add(T model) {
        checkPreConditions();
        getReference().child(model.getId()).setValue(model);
    }

    protected void checkPreConditions() {
        if (TextUtils.isEmpty(userId) && !isPublic()) {
            throw new DatabaseException("no valid userId");
        }
    }

    @Override
    public Observable<T> remove(final String id) {
        checkPreConditions();
        return get(id).map(new Func1<T, T>() {
            @Override
            public T call(T map) {
                FirebaseRepoImpl.this.getReference().child(id).removeValue();
                return map;
            }
        });
    }

    @Override
    public void update(T model) {
        checkPreConditions();
        Map<String, Object> hashMap = new HashMap<>();
        hashMap.put(model.getId(), model.toMap());
        getReference().updateChildren(hashMap);
    }

    @Override
    public Observable<Tuple<Integer, T>> observe() {
        return subject;
    }

    @Override
    public Observable<List<T>> observeAll() {
        return collectionSubject;
    }

    @Override
    public void clear() {
        checkPreConditions();
        getReference().removeValue();
    }

}
