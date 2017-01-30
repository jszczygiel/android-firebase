package com.jszczygiel.foundation.repos;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import android.text.TextUtils;

import com.jszczygiel.foundation.containers.Tuple;
import com.jszczygiel.foundation.enums.SubjectAction;
import com.jszczygiel.foundation.helpers.LoggerHelper;
import com.jszczygiel.foundation.repos.interfaces.BaseModel;
import com.jszczygiel.foundation.rx.PublishSubject;
import com.jszczygiel.foundation.rx.schedulers.SchedulerHelper;

import java.util.List;

import rx.AsyncEmitter;
import rx.Observable;
import rx.exceptions.OnErrorNotImplementedException;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

public abstract class FirebaseRepoImpl<T extends BaseModel> implements FirebaseRepo<T> {

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
    public Observable<Boolean> setUserId(String userId) {
        final boolean userIdChanged = !userId.equals(this.userId);
        this.userId = userId;
        init(userIdChanged);
        return Observable.just(true);

    }

    @Override
    public String getUserId() {
        return userId;
    }

    private void init(boolean userIdChanged) {
        if (userIdChanged && reference != null) {
            getReference().removeEventListener(reference);
            reference = null;
        }
        if (reference == null) {
            reference = getReference().addChildEventListener(new ChildEventListener() {
                @Override
                public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                    if (subject.hasObservers()) {
                        subject.onNext(
                                new Tuple<>(SubjectAction.ADDED, dataSnapshot.getValue(getType())));
                    }
                }

                @Override
                public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                    if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.CHANGED,
                                dataSnapshot.getValue(getType())));
                    }
                }

                @Override
                public void onChildRemoved(DataSnapshot dataSnapshot) {
                    if (subject.hasObservers()) {
                        subject.onNext(new Tuple<>(SubjectAction.REMOVED,
                                dataSnapshot.getValue(getType())));
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
    public Observable<T> get(final String id, final String referenceId) {
        LoggerHelper.logDebug("firebase:" + this.getClass().toString() + " get:" + id);
        checkPreConditions();
        if (TextUtils.isEmpty(id)) {
            throw new DatabaseException("no valid itemId");
        }

        return Observable.fromEmitter(new Action1<AsyncEmitter<T>>() {
            @Override
            public void call(final AsyncEmitter<T> emitter) {
                table.child(referenceId).child(id).orderByKey().addListenerForSingleValueEvent(
                        new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot dataSnapshot) {
                                T model = dataSnapshot.getValue(getType());
                                if (model != null) {
                                    emitter.onNext(model);
                                }
                                emitter.onCompleted();

                            }

                            @Override
                            public void onCancelled(DatabaseError databaseError) {
                                emitter.onError(databaseError.toException());

                            }
                        });

            }
        }, AsyncEmitter.BackpressureMode.BUFFER)
                .subscribeOn(Schedulers.newThread());

    }

    @Override
    public Observable<T> get(final String id) {
        return get(id, userId);
    }

    @Override
    public Observable<T> getAll() {
        LoggerHelper.logDebug("firebase:" + this.getClass().toString() + " getAll");
        checkPreConditions();

        return Observable.fromEmitter(new Action1<AsyncEmitter<T>>() {
            @Override
            public void call(final AsyncEmitter<T> emitter) {
                getReference().orderByKey().addListenerForSingleValueEvent(
                        new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot dataSnapshot) {
                                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                                    T model = snapshot.getValue(getType());
                                    if (model != null) {
                                        emitter.onNext(model);
                                    }
                                }
                                emitter.onCompleted();

                            }

                            @Override
                            public void onCancelled(DatabaseError databaseError) {
                                emitter.onError(databaseError.toException());

                            }
                        });
            }
        }, AsyncEmitter.BackpressureMode.BUFFER)
                .subscribeOn(Schedulers.newThread());
    }

    @Override
    public void notify(T model) {
        LoggerHelper.logDebug("firebase:" + this.getClass().toString() + " notify");

        if (subject.hasObservers()) {
            subject.onNext(new Tuple<>(SubjectAction.CHANGED, model));
        }
    }

    @Override
    public void add(T model) {
        LoggerHelper.logDebug("firebase:" + this.getClass().toString() + " add");

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
        LoggerHelper.logDebug("firebase:" + this.getClass().toString() + " remove");
        checkPreConditions();
        return get(id).observeOn(SchedulerHelper.getDatabaseWriterScheduler()).map(
                new Func1<T, T>() {
                    @Override
                    public T call(T map) {
                        FirebaseRepoImpl.this.getReference().child(id).removeValue();
                        return map;
                    }
                });
    }

    @Override
    public void update(final T model) {
        checkPreConditions();
        LoggerHelper.logDebug("firebase:" + this.getClass().toString() + " update");
        get(model.getId()).observeOn(SchedulerHelper.getDatabaseWriterScheduler()).subscribe(
                new Action1<T>() {
                    @Override
                    public void call(T next) {
                        FirebaseRepoImpl.this.getReference().updateChildren(model.toMap(next));
                    }
                }, new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        throw new OnErrorNotImplementedException(throwable);
                    }
                });

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
        LoggerHelper.logDebug("firebase:" + this.getClass().toString() + " update");

        checkPreConditions();
        getReference().removeValue();
    }

}
